package org.keinus.logparser.domain.input.model.snmp;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.snmp4j.mp.MPv3;
import org.snmp4j.smi.OctetString;

/** Only engine identity/boots are persisted. Notifications remain in a bounded memory queue. */
final class SnmpEngineState implements Closeable {
    private static final ObjectMapper JSON = new ObjectMapper();
    final OctetString engineId;
    final int boots;
    private final FileChannel lockChannel;
    private final FileLock lock;

    SnmpEngineState(Path path, String configuredEngineId) throws IOException {
        path = path.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        lockChannel = FileChannel.open(Path.of(path + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("SNMP engine state is already in use: " + path);
            boolean exists = Files.exists(path);
            JsonNode saved = exists ? JSON.readTree(Files.readAllBytes(path)) : null;
            if (exists && (saved == null || !saved.hasNonNull("engineId") || !saved.path("boots").isIntegralNumber() || !saved.path("boots").canConvertToInt()
                    || saved.path("boots").asInt() < 0 || saved.path("boots").asInt() >= Integer.MAX_VALUE - 1)) {
                throw new IOException("Invalid or exhausted SNMP engine state: " + path);
            }
            engineId = saved != null ? SnmpSecurity.engineId(saved.get("engineId").asText())
                    : configuredEngineId != null ? SnmpSecurity.engineId(configuredEngineId)
                    : new OctetString(MPv3.createLocalEngineID());
            if (configuredEngineId != null && !engineId.equals(SnmpSecurity.engineId(configuredEngineId))) {
                throw new IOException("Configured SNMP engineId differs from persisted engine state");
            }
            boots = saved == null ? 1 : saved.get("boots").asInt() + 1;
            Path temporary = Files.createTempFile(path.getParent(), "snmp-engine-", ".tmp");
            try {
                byte[] content = JSON.writeValueAsBytes(Map.of("engineId", engineId.toHexString(), "boots", boots));
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(content);
                    while (buffer.hasRemaining()) output.write(buffer);
                    output.force(true);
                }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            lock = acquired;
        } catch (Exception e) {
            if (acquired != null) acquired.release();
            lockChannel.close();
            throw new IOException("Cannot initialize SNMP engine state: " + path, e);
        }
    }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { lockChannel.close(); }
    }
}
