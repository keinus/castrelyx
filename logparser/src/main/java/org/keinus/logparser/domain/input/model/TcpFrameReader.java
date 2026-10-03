package org.keinus.logparser.domain.input.model;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded byte framing; octet counts are measured before UTF-8 decoding. */
final class TcpFrameReader {
    private final InputStream input;
    private final boolean counted;
    private final int maximum;
    TcpFrameReader(InputStream input, String framing, int maximum) {
        this.input = new BufferedInputStream(input);
        this.counted = "octet-counted".equals(framing);
        this.maximum = maximum;
    }
    String read() throws IOException {
        int first = input.read();
        if (first < 0) return null;
        if (counted) {
            if (first < '1' || first > '9') throw new IOException("Invalid octet-counted frame length");
            long size = first - '0';
            int c;
            while ((c = input.read()) != ' ') {
                if (c < '0' || c > '9' || size > maximum) throw new IOException("Invalid frame length");
                size = size * 10 + c - '0';
            }
            if (size > maximum) throw new IOException("Frame exceeds maxFrameBytes");
            byte[] bytes = input.readNBytes((int) size);
            if (bytes.length != size) throw new EOFException("Incomplete octet-counted frame");
            return new String(bytes, StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int c = first;
        while (c >= 0 && c != '\n') {
            if (bytes.size() >= maximum) throw new IOException("Line exceeds maxFrameBytes");
            bytes.write(c); c = input.read();
        }
        byte[] value = bytes.toByteArray();
        int length = value.length;
        if (length > 0 && value[length-1] == '\r') length--;
        return new String(value, 0, length, StandardCharsets.UTF_8);
    }
}
