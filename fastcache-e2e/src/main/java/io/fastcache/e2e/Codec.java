package io.fastcache.e2e;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one codec used by every arm that crosses a process boundary.
 *
 * <p><b>Why not Jackson.</b> JSON would base64-encode a 25 MB payload and inflate it by a third, so the
 * benchmark would spend its time in the encoder and measure Jackson rather than the cache. This is a
 * length-prefixed binary format: metadata and structure first, bulk payload appended raw.
 *
 * <p><b>The bias this introduces is worth naming.</b> A hand-rolled binary codec is faster than Jackson,
 * Kryo or protobuf, and it is used only by the arms that need serialization at all — the FastCache sidecar
 * and Redis. So it flatters exactly the two arms whose cost we are trying to establish. A production
 * service would pay more. The codec is therefore also measured standalone, with no cache in the path, so
 * its contribution can be subtracted rather than assumed away.
 */
public final class Codec {

    private Codec() {
    }

    public static byte[] encode(LargeResponse response) {
        // Sized generously up front: growing a ByteArrayOutputStream past a 25 MB payload would copy the
        // array repeatedly and charge the cache for the codec's allocation strategy.
        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream(response.payload().length + 64 * 1024);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeUTF(response.documentId());
            out.writeUTF(response.title());
            out.writeLong(response.generatedAtMillis());

            out.writeInt(response.metadata().size());
            for (Map.Entry<String, String> entry : response.metadata().entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }

            out.writeInt(response.sections().size());
            for (LargeResponse.Section section : response.sections()) {
                out.writeInt(section.index());
                out.writeUTF(section.heading());
                out.writeUTF(section.body());
                out.writeDouble(section.score());
                out.writeLong(section.revision());
            }

            out.writeInt(response.payload().length);
            out.write(response.payload());
        } catch (IOException e) {
            throw new UncheckedIOException("encode failed", e);
        }
        return buffer.toByteArray();
    }

    public static LargeResponse decode(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            String documentId = in.readUTF();
            String title = in.readUTF();
            long generatedAt = in.readLong();

            int metaCount = in.readInt();
            Map<String, String> metadata = new LinkedHashMap<>(Math.max(4, metaCount * 2));
            for (int i = 0; i < metaCount; i++) {
                metadata.put(in.readUTF(), in.readUTF());
            }

            int sectionCount = in.readInt();
            List<LargeResponse.Section> sections = new ArrayList<>(sectionCount);
            for (int i = 0; i < sectionCount; i++) {
                sections.add(new LargeResponse.Section(
                        in.readInt(), in.readUTF(), in.readUTF(), in.readDouble(), in.readLong()));
            }

            int payloadLength = in.readInt();
            byte[] payload = new byte[payloadLength];
            in.readFully(payload);

            return new LargeResponse(documentId, title, generatedAt, metadata, sections, payload);
        } catch (IOException e) {
            throw new UncheckedIOException("decode failed", e);
        }
    }
}
