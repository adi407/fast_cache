package io.fastcache.engine.metrics;

/**
 * Renders the single-page console.
 *
 * <h2>Shape of the page</h2>
 * The server emits a complete, already-populated HTML document with the current snapshot inlined as a JSON
 * bootstrap, then a small script re-polls {@code /metrics} once a second and updates the numbers in place.
 * Three consequences, all deliberate:
 * <ul>
 *   <li>The page is correct and readable with JavaScript disabled — it just stops updating.</li>
 *   <li>There is no loading flash: the first paint already has real data.</li>
 *   <li>No build step, no framework, no CDN. One file, no network dependencies, works offline on a
 *       laptop with no internet — which is where a local cache console mostly gets opened.</li>
 * </ul>
 *
 * <h2>The ticking savings figure</h2>
 * The dollar counter eases between polled values rather than jumping once a second. That is presentation,
 * not invention: it only ever interpolates toward a number the server actually reported, and it is clamped
 * to that number as soon as it arrives. A counter that visibly moves is how you tell at a glance that the
 * cache is doing work right now.
 */
final class Dashboard {

    private Dashboard() {
    }

    static String render(MetricsSnapshot snapshot) {
        return TEMPLATE.replace("__BOOTSTRAP__", snapshot.toJson());
    }

    private static final String TEMPLATE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>FastCache Console</title>
            <style>
              :root {
                --bg: #f6f7f9;       --panel: #ffffff;    --ink: #16181d;
                --muted: #6b7280;    --line: #e5e7eb;     --accent: #2563eb;
                --good: #059669;     --warn: #d97706;     --bad: #dc2626;
                --track: #eceef1;    --mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
              }
              @media (prefers-color-scheme: dark) {
                :root {
                  --bg: #0f1115;     --panel: #171a21;    --ink: #e7e9ee;
                  --muted: #9aa3b2;  --line: #262b35;     --accent: #60a5fa;
                  --good: #34d399;   --warn: #fbbf24;     --bad: #f87171;
                  --track: #232833;
                }
              }
              * { box-sizing: border-box; }
              body {
                margin: 0; background: var(--bg); color: var(--ink);
                font: 15px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                padding: 24px 16px 48px;
              }
              .wrap { max-width: 1120px; margin: 0 auto; }
              header { display: flex; flex-wrap: wrap; align-items: baseline; gap: 12px; margin-bottom: 20px; }
              h1 { font-size: 20px; margin: 0; letter-spacing: -0.01em; }
              h1 span { color: var(--accent); }
              .meta { color: var(--muted); font-size: 13px; font-family: var(--mono); }
              .grid { display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); }
              .card {
                background: var(--panel); border: 1px solid var(--line); border-radius: 12px;
                padding: 18px 20px;
              }
              .card.span { grid-column: 1 / -1; }
              h2 {
                font-size: 12px; text-transform: uppercase; letter-spacing: 0.08em;
                color: var(--muted); margin: 0 0 14px; font-weight: 600;
              }
              .hero { font-size: 46px; font-weight: 700; letter-spacing: -0.03em; font-variant-numeric: tabular-nums; }
              .hero.good { color: var(--good); }
              .sub { color: var(--muted); font-size: 13px; margin-top: 4px; }
              .row { display: flex; justify-content: space-between; gap: 12px; padding: 7px 0; font-size: 14px; }
              .row + .row { border-top: 1px solid var(--line); }
              .row span:last-child { font-family: var(--mono); font-variant-numeric: tabular-nums; }
              .bar-label { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 6px; }
              .bar-label b { font-family: var(--mono); font-weight: 500; }
              .track { background: var(--track); border-radius: 999px; height: 9px; overflow: hidden; }
              .fill { height: 100%; border-radius: 999px; background: var(--accent); transition: width .4s ease; }
              .fill.good { background: var(--good); } .fill.warn { background: var(--warn); }
              .fill.bad { background: var(--bad); }
              .bar + .bar { margin-top: 16px; }
              .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 14px; }
              .stat b { display: block; font-size: 22px; font-variant-numeric: tabular-nums; letter-spacing: -0.02em; }
              .stat span { color: var(--muted); font-size: 12px; }
              select {
                background: var(--panel); color: var(--ink); border: 1px solid var(--line);
                border-radius: 8px; padding: 5px 9px; font: inherit; font-size: 13px;
              }
              .shards { display: flex; align-items: flex-end; gap: 2px; height: 64px; margin-top: 6px; }
              .shards i { flex: 1; background: var(--accent); border-radius: 2px 2px 0 0; min-height: 2px; opacity: .8; }
              .pill {
                display: inline-block; padding: 2px 9px; border-radius: 999px;
                font-size: 12px; font-family: var(--mono);
              }
              .pill.ok { background: color-mix(in srgb, var(--good) 15%, transparent); color: var(--good); }
              .pill.bad { background: color-mix(in srgb, var(--bad) 15%, transparent); color: var(--bad); }
              footer { color: var(--muted); font-size: 12px; margin-top: 22px; }
            </style>
            </head>
            <body>
            <div class="wrap">
              <header>
                <h1>Fast<span>Cache</span> Console</h1>
                <div class="meta" id="meta">&mdash;</div>
                <div style="margin-left:auto"><span class="pill ok" id="writes-pill">accepting writes</span></div>
              </header>

              <div class="grid">
                <div class="card">
                  <h2>Estimated savings
                    <select id="model" style="float:right;margin-top:-4px">
                      <option value="gpt-4o">GPT-4o &mdash; $2.50/M</option>
                      <option value="claude-3-5-sonnet">Claude 3.5 Sonnet &mdash; $3.00/M</option>
                    </select>
                  </h2>
                  <div class="hero good" id="saved">$0.00</div>
                  <div class="sub" id="saved-sub">&mdash;</div>
                  <div style="margin-top:14px">
                    <div class="row"><span>Cost without FastCache</span><span id="projected">$0.00</span></div>
                    <div class="row"><span>Cost actually incurred</span><span id="actual">$0.00</span></div>
                    <div class="row"><span>Input tokens avoided</span><span id="tokens">0</span></div>
                    <div class="row"><span>Characters served from cache</span><span id="chars">0</span></div>
                  </div>
                </div>

                <div class="card">
                  <h2>Memory</h2>
                  <div class="bar">
                    <div class="bar-label"><span>System RAM</span><b id="sys-txt">&mdash;</b></div>
                    <div class="track"><div class="fill" id="sys-bar" style="width:0%"></div></div>
                  </div>
                  <div class="bar">
                    <div class="bar-label"><span>Off-heap cache / budget</span><b id="off-txt">&mdash;</b></div>
                    <div class="track"><div class="fill" id="off-bar" style="width:0%"></div></div>
                  </div>
                  <div class="bar">
                    <div class="bar-label"><span>JVM heap</span><b id="heap-txt">&mdash;</b></div>
                    <div class="track"><div class="fill" id="heap-bar" style="width:0%"></div></div>
                  </div>
                  <div class="bar">
                    <div class="bar-label"><span>Process CPU</span><b id="cpu-txt">&mdash;</b></div>
                    <div class="track"><div class="fill" id="cpu-bar" style="width:0%"></div></div>
                  </div>
                  <div class="sub" style="margin-top:14px">
                    Payloads live off-heap, so heap stays flat no matter how large the cache grows.
                  </div>
                </div>

                <div class="card span">
                  <h2>Latency &amp; runtime</h2>
                  <div class="stats">
                    <div class="stat"><b id="l-get-p50">&mdash;</b><span>get p50</span></div>
                    <div class="stat"><b id="l-get-p99">&mdash;</b><span>get p99</span></div>
                    <div class="stat"><b id="l-put-p50">&mdash;</b><span>put p50</span></div>
                    <div class="stat"><b id="l-put-p99">&mdash;</b><span>put p99</span></div>
                    <div class="stat"><b id="r-syscpu">&mdash;</b><span>system CPU</span></div>
                    <div class="stat"><b id="r-gc">0</b><span>GC collections</span></div>
                    <div class="stat"><b id="r-gctime">0 ms</b><span>GC total pause</span></div>
                    <div class="stat"><b id="r-threads">0</b><span>platform threads</span></div>
                  </div>
                  <div class="sub">
                    Percentiles are bucket-interpolated. Platform threads exclude virtual threads &mdash;
                    that count staying flat while connections climb is the point of the design.
                  </div>
                </div>

                <div class="card span">
                  <h2>Telemetry</h2>
                  <div class="stats">
                    <div class="stat"><b id="t-req">0</b><span>total requests</span></div>
                    <div class="stat"><b id="t-hit">0</b><span>engine hits</span></div>
                    <div class="stat"><b id="t-l1">0</b><span>L1 short-circuits</span></div>
                    <div class="stat"><b id="t-miss">0</b><span>misses</span></div>
                    <div class="stat"><b id="t-ratio">0%</b><span>hit rate</span></div>
                    <div class="stat"><b id="t-stale">0</b><span>stale-served</span></div>
                    <div class="stat"><b id="t-herd">0</b><span>stampedes stopped</span></div>
                    <div class="stat"><b id="t-entries">0</b><span>entries</span></div>
                    <div class="stat"><b id="t-evict">0</b><span>evictions</span></div>
                    <div class="stat"><b id="t-skew">1.00</b><span>shard skew</span></div>
                  </div>
                  <div class="shards" id="shards"></div>
                  <div class="sub">Entries per shard &mdash; a single tall bar means a hot key needs client-side L1.</div>
                </div>
              </div>

              <footer>
                Savings are an estimate: characters &divide; 4 as an input-token proxy, priced at the
                selected model's published input rate. Output tokens are not counted. Read-only console.
                Scrape <code>/metrics/prometheus</code> for these numbers in your own monitoring.
              </footer>
            </div>

            <script>
            const boot = __BOOTSTRAP__;
            const $ = id => document.getElementById(id);
            const usd = n => '$' + n.toLocaleString('en-US', {minimumFractionDigits: 2, maximumFractionDigits: 2});
            const num = n => Math.round(n).toLocaleString('en-US');
            const bytes = b => {
              if (!b) return '0 B';
              const u = ['B','KB','MB','GB','TB'];
              const i = Math.min(u.length - 1, Math.floor(Math.log(b) / Math.log(1024)));
              return (b / Math.pow(1024, i)).toFixed(i ? 1 : 0) + ' ' + u[i];
            };
            const pct = r => (r * 100).toFixed(1) + '%';

            function bar(id, ratio, used, total) {
              const clamped = Math.max(0, Math.min(1, ratio || 0));
              const fill = $(id + '-bar');
              fill.style.width = (clamped * 100).toFixed(1) + '%';
              // Thresholds mirror the engine's own 0.85 rejection ratio, so the bar turns red at exactly
              // the point writes start being shed rather than at an arbitrary design choice.
              fill.className = 'fill ' + (clamped >= 0.85 ? 'bad' : clamped >= 0.70 ? 'warn' : 'good');
              if (total > 0) {
                $(id + '-txt').textContent = bytes(used) + ' / ' + bytes(total) + '  (' + pct(clamped) + ')';
              }
            }

            // Eased toward the server's reported value; never above it.
            let shown = 0, target = 0;
            function tick() {
              const delta = target - shown;
              shown = Math.abs(delta) < 1e-9 ? target : shown + delta * 0.12;
              $('saved').textContent = usd(shown);
              requestAnimationFrame(tick);
            }

            function apply(m) {
              target = m.savings.saved_usd;
              if (shown > target) shown = target;

              $('saved-sub').textContent = pct(m.savings.savings_ratio) + ' of the projected '
                + m.savings.model + ' bill avoided';
              $('projected').textContent = usd(m.savings.projected_cost_usd);
              $('actual').textContent = usd(m.savings.actual_cost_usd);
              $('tokens').textContent = num(m.savings.tokens_avoided);
              $('chars').textContent = num(m.savings.characters_avoided);

              bar('sys', m.memory.system_used_ratio, m.memory.system_used_bytes, m.memory.system_total_bytes);
              bar('off', m.memory.offheap_used_ratio, m.memory.offheap_reserved_bytes, m.memory.offheap_budget_bytes);
              bar('heap', m.memory.heap_used_ratio, m.memory.heap_used_bytes, m.memory.heap_max_bytes);

              const ms = v => (v === undefined || v === null) ? '—'
                : (v < 1 ? v.toFixed(2) + ' ms' : v.toFixed(1) + ' ms');
              const lat = m.latency || {};
              $('l-get-p50').textContent = ms(lat.get && lat.get.p50_ms);
              $('l-get-p99').textContent = ms(lat.get && lat.get.p99_ms);
              $('l-put-p50').textContent = ms(lat.put && lat.put.p50_ms);
              $('l-put-p99').textContent = ms(lat.put && lat.put.p99_ms);

              const rt = m.runtime || {};
              // -1 is the JVM's "not yet sampled", not zero. Showing a dash is honest; showing 0% is not.
              const cpuRatio = rt.process_cpu_ratio;
              if (cpuRatio >= 0) {
                bar('cpu', cpuRatio, 0, 0);
                $('cpu-txt').textContent = pct(cpuRatio) + ' of ' + (rt.available_processors || '?') + ' cores';
              } else {
                $('cpu-txt').textContent = 'not yet sampled';
              }
              $('r-syscpu').textContent = rt.system_cpu_ratio >= 0 ? pct(rt.system_cpu_ratio) : '—';
              $('r-gc').textContent = num(rt.gc_collections || 0);
              $('r-gctime').textContent = num(rt.gc_time_ms || 0) + ' ms';
              $('r-threads').textContent = num(rt.platform_threads || 0);

              $('t-req').textContent = num(m.cache.total_requests);
              $('t-hit').textContent = num(m.cache.hits);
              $('t-l1').textContent = num(m.clients.l1_hits);
              $('t-miss').textContent = num(m.cache.misses);
              $('t-ratio').textContent = pct(m.cache.overall_hit_ratio);
              $('t-stale').textContent = num(m.cache.stale_hits);
              $('t-herd').textContent = num(m.cache.herd_suppressed);
              $('t-entries').textContent = num(m.cache.entries);
              $('t-evict').textContent = num(m.cache.ttl_evictions + m.cache.lru_evictions);
              $('t-skew').textContent = m.cache.shard_skew.toFixed(2);

              const pill = $('writes-pill');
              pill.textContent = m.memory.rejecting_writes ? 'shedding writes' : 'accepting writes';
              pill.className = 'pill ' + (m.memory.rejecting_writes ? 'bad' : 'ok');

              $('meta').textContent = 'v' + m.version + ' \\u00b7 up ' + Math.floor(m.uptime_ms / 1000) + 's \\u00b7 '
                + m.cache.shards + ' shards \\u00b7 ' + m.clients.connected + ' client(s)';

              const peak = Math.max(1, ...m.shards.map(s => s.entries));
              $('shards').innerHTML = m.shards
                .map(s => '<i style="height:' + (s.entries / peak * 100).toFixed(1) + '%" title="shard '
                  + s.index + ': ' + s.entries + ' entries"></i>').join('');
            }

            async function poll() {
              try {
                const res = await fetch('/metrics?model=' + encodeURIComponent($('model').value),
                                       {cache: 'no-store'});
                if (res.ok) apply(await res.json());
              } catch (e) {
                // A failed poll means the sidecar went away. Keep the last good numbers on screen rather
                // than blanking the page — frozen data with a stale uptime is more diagnostic than zeros.
              }
            }

            $('model').value = boot.savings.model_id;
            apply(boot);
            requestAnimationFrame(tick);
            $('model').addEventListener('change', poll);
            setInterval(poll, 1000);
            </script>
            </body>
            </html>
            """;
}
