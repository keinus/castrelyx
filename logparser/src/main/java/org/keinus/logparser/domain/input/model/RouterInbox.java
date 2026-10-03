package org.keinus.logparser.domain.input.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.keinus.logparser.domain.model.LogEvent;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Receiver-side durable retry queue. TCP receipt is not a durable sender ACK. */
public final class RouterInbox {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;
    private final Map<Path, Long> leases = new HashMap<>();
    private static final Map<Path, RouterInbox> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Path, String> owners = new HashMap<>();
    private final Map<String, State> states = new HashMap<>();
    private final Map<Path,Long> sizes = new HashMap<>();
    private long totalBytes;
    private int totalFiles;
    private static final class State { long pending, quarantined; String received="", stored=""; }
    private static final long MAX_BYTES = 256L << 20;
    RouterInbox(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        if (ACTIVE.containsKey(this.root)) throw new IOException("Router inbox is already in use: " + this.root);
        Files.createDirectories(this.root);
        Files.createDirectories(this.root.resolve("dead"));
        restrictPermissions(this.root, "rwx------");
        restrictPermissions(this.root.resolve("dead"), "rwx------");
        try (var files=Files.list(this.root)) {
            for(Path path:files.filter(p->p.toString().endsWith(".tmp")).toList()) {
                try {
                    var recovered = JSON.readTree(Files.readAllBytes(path));
                    if (recovered == null || !recovered.hasNonNull("text") || !recovered.hasNonNull("receivedAt")) {
                        Files.move(path,this.root.resolve("dead").resolve(path.getFileName()),StandardCopyOption.ATOMIC_MOVE);
                        continue;
                    }
                    Files.move(path,path.resolveSibling(path.getFileName().toString().replace(".tmp",".json")),StandardCopyOption.ATOMIC_MOVE);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    Files.move(path,this.root.resolve("dead").resolve(path.getFileName()),StandardCopyOption.ATOMIC_MOVE);
                }
            }
        }
        try(var files=Files.walk(this.root,2)) {
            for(Path path:files.filter(Files::isRegularFile).toList()) {
                long bytes=Files.size(path);totalBytes+=bytes;totalFiles++;
                if(!path.toString().endsWith(".json"))continue;
                sizes.put(path,bytes);
                var data=JSON.readTree(Files.readAllBytes(path));
                String asset=data.path("asset").asText();
                State state=states.computeIfAbsent(asset,key->new State());
                if(path.getParent().equals(this.root)) {state.pending++;owners.put(path,asset);} else state.quarantined++;
                String received=data.path("receivedAt").asText();
                if(received.compareTo(state.received)>0)state.received=received;
            }
        }
        if (ACTIVE.putIfAbsent(this.root,this) != null) throw new IOException("Router inbox is already in use: " + this.root);
    }
    private static void restrictPermissions(Path path, String permissions) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(permissions));
    }
    synchronized void register(String asset) { states.computeIfAbsent(asset,key->new State()); }
    void close() { ACTIVE.remove(root,this); }
    public static Map<String,Object> status(String asset) {
        long pending=0,quarantined=0;String received="",stored="";boolean configured=false;
        for(RouterInbox inbox:ACTIVE.values()) {
            synchronized(inbox) {
                State state=inbox.states.get(asset);if(state==null)continue;
                configured=true;pending+=state.pending;quarantined+=state.quarantined;
                if(state.received.compareTo(received)>0)received=state.received;
                if(state.stored.compareTo(stored)>0)stored=state.stored;
            }
        }
        return Map.of("available",true,"configured",configured,"pendingRecords",pending,"quarantinedRecords",quarantined,"lastReceivedAt",received,"lastStoredAt",stored);
    }
    synchronized void append(String text, String source, String asset) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(Map.of("text", text, "source", source, "asset", asset, "receivedAt", Instant.now().toString()));
        if (totalFiles >= 10000 || totalBytes + bytes.length > MAX_BYTES) throw new IOException("Router inbox/DLQ full; ingestion paused");
        String id = UUID.randomUUID().toString();
        Path temporary = root.resolve(id + ".tmp");
        Path target=root.resolve(id + ".json");
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                restrictPermissions(temporary, "rw-------");
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException e) { Files.deleteIfExists(temporary); throw e; }
        totalBytes+=bytes.length;totalFiles++;sizes.put(target,(long)bytes.length);
        owners.put(target,asset);
        State state=states.computeIfAbsent(asset,key->new State());state.pending++;state.received=Instant.now().toString();
    }
    synchronized LogEvent poll() throws IOException {
        long now = System.currentTimeMillis();
        try (var files = Files.list(root)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                if (leases.getOrDefault(path, 0L) > now) continue;
                var data = JSON.readTree(Files.readAllBytes(path));
                LogEvent event = new LogEvent(data.path("text").asText(), data.path("source").asText(), "security-router");
                event.setTimestamp(Instant.parse(data.path("receivedAt").asText()));
                event.setAuthenticatedSourceId(data.path("source").asText());
                event.setAuthenticatedAssetUid(data.path("asset").asText());
                event.setStoredCallback(() -> complete(path));
                event.setQuarantineCallback(reason -> quarantine(path, reason));
                // A dropped dispatcher item or failed DB write remains retryable.
                leases.put(path, now + 30000);
                return event;
            }
        }
        return null;
    }
    private synchronized void complete(Path path) {
        try {
            Files.deleteIfExists(path); leases.remove(path);
            String owner=owners.remove(path);
            Long size=sizes.remove(path);if(size!=null){totalBytes-=size;totalFiles--;}
            if(owner!=null){State state=states.get(owner);state.pending--;state.stored=Instant.now().toString();}
        }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private synchronized void quarantine(Path path, String reason) {
        try {
            if (!Files.exists(path)) return;
            // The original record stays intact. Reason is diagnostic data only.
            byte[] detail=reason.substring(0, Math.min(reason.length(), 2048)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(totalBytes+detail.length>MAX_BYTES || totalFiles>=10000)throw new IOException("DLQ full");
            Files.write(root.resolve("dead").resolve(path.getFileName()+".reason"), detail);
            totalBytes+=detail.length;totalFiles++;
            Files.move(path, root.resolve("dead").resolve(path.getFileName()), StandardCopyOption.ATOMIC_MOVE);
            leases.remove(path);
            String owner=owners.remove(path);
            if(owner!=null){State state=states.get(owner);state.pending--;state.quarantined++;}
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
