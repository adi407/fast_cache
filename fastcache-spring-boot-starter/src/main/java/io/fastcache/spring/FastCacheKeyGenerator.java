package io.fastcache.spring;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an invocation into a shard key, either by evaluating the annotation's SpEL or by deriving one.
 *
 * <p>Parsed expressions are memoised per {@code (method, expression)} pair: SpEL parsing costs far more
 * than the cache lookup it is guarding, so re-parsing on every call would make the cache slower than the
 * method it caches.
 */
final class FastCacheKeyGenerator {

    /** Above this length, a generated key is digested — long keys blow up the frame header's 64 KiB limit. */
    private static final int MAX_INLINE_KEY_CHARS = 200;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();
    private final ConcurrentHashMap<ExpressionKey, Expression> expressionCache = new ConcurrentHashMap<>();

    private record ExpressionKey(Method method, String expression) {
    }

    /** Variables this generator binds regardless of whether parameter names are available. */
    private static final Set<String> BUILTIN_VARIABLES = Set.of("root", "method", "result");

    private static final Pattern VARIABLE_REFERENCE = Pattern.compile("#([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern POSITIONAL_VARIABLE = Pattern.compile("[pa]\\d+");

    /**
     * Reports whether this method's SpEL expressions can actually be evaluated.
     *
     * <p><b>Why this check exists.</b> {@code DefaultParameterNameDiscoverer} can only recover argument
     * names from a class compiled with {@code -parameters}. Spring Boot's build plugins pass that flag by
     * default, but a hand-rolled build may not — and the failure mode is genuinely dangerous rather than
     * merely inconvenient. With names unavailable, {@code "#tenant + ':' + #query"} evaluates every
     * variable to null and yields the constant key {@code "null:null"}, so <em>every</em> call to that
     * method collides on one entry and callers start receiving each other's results. For a
     * tenant-scoped cache that is a cross-tenant data leak.
     *
     * <p>So: detect it once per method, refuse to cache, and say why. A method that runs uncached is a
     * performance regression; a method that serves another tenant's rows is an incident.
     *
     * @param expressions the {@code key}, {@code condition} and {@code unless} expressions to check
     * @return false when an expression names a parameter this JVM cannot resolve
     */
    boolean canResolveVariables(Method method, String... expressions) {
        if (parameterNames.getParameterNames(method) != null) {
            return true;
        }
        for (String expression : expressions) {
            if (!StringUtils.hasText(expression)) {
                continue;
            }
            Matcher matcher = VARIABLE_REFERENCE.matcher(expression);
            while (matcher.find()) {
                String variable = matcher.group(1);
                // #p0 / #a0 are positional and always bound, so they stay usable without -parameters.
                if (BUILTIN_VARIABLES.contains(variable) || POSITIONAL_VARIABLE.matcher(variable).matches()) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    /** Builds the full namespaced key for an invocation. */
    String generate(FastCache annotation, Method method, Object target, Object[] args) {
        String namespace = StringUtils.hasText(annotation.namespace())
                ? annotation.namespace()
                : method.getDeclaringClass().getName();

        String body = StringUtils.hasText(annotation.key())
                ? String.valueOf(evaluate(annotation.key(), method, target, args, null))
                : deriveKey(method, args);

        return namespace + '#' + method.getName() + '#' + body;
    }

    /**
     * Evaluates a {@code condition} guard: "may this call use the cache?". A blank expression is vacuously
     * true, so an unset {@code condition} never blocks caching.
     */
    boolean evaluateCondition(String expression, Method method, Object target, Object[] args, Object result) {
        if (!StringUtils.hasText(expression)) {
            return true;
        }
        return asBoolean(evaluate(expression, method, target, args, result));
    }

    /**
     * Evaluates an {@code unless} veto: "must this result be kept out of the cache?".
     *
     * <p>Deliberately <em>not</em> the negation of {@link #evaluateCondition}: an unset {@code unless} must
     * mean "no veto" (false), while an unset {@code condition} means "allowed" (true). Folding the two into
     * one method inverts the empty case and silently caches exactly the results the developer wrote
     * {@code unless} to exclude.
     */
    boolean evaluateVeto(String expression, Method method, Object target, Object[] args, Object result) {
        if (!StringUtils.hasText(expression)) {
            return false;
        }
        return asBoolean(evaluate(expression, method, target, args, result));
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean bool ? bool : value != null;
    }

    private Object evaluate(String expression, Method method, Object target, Object[] args, Object result) {
        Expression parsed = expressionCache.computeIfAbsent(
                new ExpressionKey(method, expression), key -> parser.parseExpression(key.expression()));

        StandardEvaluationContext context = new StandardEvaluationContext(target);
        context.setVariable("root", target);
        context.setVariable("method", method.getName());
        context.setVariable("result", result);

        String[] names = parameterNames.getParameterNames(method);
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                context.setVariable(names[i], args[i]);
                context.setVariable("p" + i, args[i]);
                context.setVariable("a" + i, args[i]);
            }
        }
        return parsed.getValue(context);
    }

    /**
     * Derives a key from the argument values. {@code Arrays.deepToString} gives stable, readable keys for
     * records, boxed primitives, strings and collections &mdash; which is what method arguments almost
     * always are. Long renderings collapse to a SHA-256 prefix so a 40 KB prompt argument does not become
     * a 40 KB cache key.
     */
    private String deriveKey(Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return "()";
        }
        String rendered = Arrays.deepToString(args);
        if (rendered.length() <= MAX_INLINE_KEY_CHARS) {
            return rendered;
        }
        return "sha256:" + digest(method.toGenericString() + rendered);
    }

    private static String digest(String value) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS; if it is missing the JVM is broken beyond our concern.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
