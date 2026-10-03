package org.keinus.logparser.domain.input.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keinus.logparser.application.pipeline.RouterEventNormalizer;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.parse.model.*;
import org.keinus.logparser.domain.output.model.ClickHouseOutputAdapter;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class RouterPipelineTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON=new ObjectMapper();
    static String wire(String source,String family,String raw) {
        return "<134>1 2026-10-03T00:00:00Z device security-switch - - - {\"schema_version\":\"security-router.event/v1\",\"source_id\":\""+source+"\",\"log_family\":\""+family+"\",\"payload\":"+raw+"}";
    }
    static void parse(LogEvent event) {
        assertTrue(new RFC5424SyslogParser().parse(event));
        var inner=new LogEvent(String.valueOf(event.getField("syslog_MESSAGE")));
        assertTrue(new JsonParser().parse(inner));
        event.setField("syslog_MESSAGE",inner.getFields());
        RouterEventNormalizer.normalize(event);
    }
    @Test void retainsOnDatabaseFailureAndReplaysAfterRestartUntilCanonicalWrite() throws Exception {
        var inbox=new RouterInbox(dir);
        inbox.append(wire("ssw-1","audit","{\"id\":\"ev-1\",\"at\":\"2026-10-03T00:00:00Z\",\"kind\":\"authentication.failure\",\"actor\":{\"id\":\"alice\"},\"result\":\"denied\",\"detail\":\"인증 실패\",\"hash\":\"abc\"}"),"ssw-1","asset-1");
        var event=inbox.poll();parse(event);
        AtomicBoolean fail=new AtomicBoolean(true);
        List<String> requests=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            requests.add(body);
            int status=body.contains("SECURITY_ROUTER")&&fail.get()?503:200;
            exchange.sendResponseHeaders(status,0);exchange.getResponseBody().close();
        });server.start();
        try(var output=new ClickHouseOutputAdapter(Map.of("configParams",JSON.writeValueAsString(Map.of(
            "endpointUrl","http://127.0.0.1:"+server.getAddress().getPort(),"autoCreateSchema",false,"writeTelemetryTables",true,"incompleteChunkDlqDir",dir.resolve("chunks").toString()))))) {
            assertThrows(RuntimeException.class,()->output.send(event));
            inbox.close();
            var restarted=new RouterInbox(dir);var replay=restarted.poll();
            assertNotNull(replay);assertEquals(event.getTimestamp(),replay.getTimestamp());parse(replay);
            fail.set(false);output.send(replay);
            restarted.close();
            var empty=new RouterInbox(dir);assertNull(empty.poll());empty.close();
            String canonical=requests.stream().filter(v->v.contains("\"source_type\":\"SECURITY_ROUTER\"")).findFirst().orElseThrow();
            var row=JSON.readTree(canonical.trim());
            assertEquals("asset-1",row.path("asset_uid").asText());
            var payload=JSON.readTree(row.path("event_json").asText());
            assertEquals("ev-1",payload.path("event_id").asText());
            assertEquals("abc",payload.path("raw").path("hash").asText());
            assertEquals("ERROR",row.path("severity").asText());
        } finally {server.stop(0);}
    }
    @Test void quarantinesMismatchedIdentityAndPreservesRawRecord() throws Exception {
        var inbox=new RouterInbox(dir);
        inbox.append(wire("spoofed","audit","{}"),"ssw-1","asset-1");
        var event=inbox.poll();
        var error=assertThrows(IllegalArgumentException.class,()->parse(event));
        event.quarantine(error.getMessage());
        inbox.close();
        var empty=new RouterInbox(dir);assertNull(empty.poll());empty.close();
        try(var files=Files.list(dir.resolve("dead"))) { assertTrue(files.anyMatch(p->p.toString().endsWith(".json"))); }
    }
    @Test void normalizesZeekJsonWithoutFlatteningDottedKeys() throws Exception {
        var inbox=new RouterInbox(dir);
        inbox.append(wire("ssw-1","zeek","{\"id\":\"z-1\",\"at\":\"2026-10-03T00:00:00Z\",\"kind\":\"zeek.notice\",\"data\":{\"id.orig_h\":\"10.0.0.1\",\"uid\":\"C1\"}}"),"ssw-1","asset-1");
        var event=inbox.poll();parse(event);
        var payload=JSON.valueToTree(event.getField("payload"));
        assertEquals("WARNING",payload.path("severity").asText());
        assertEquals("10.0.0.1",payload.path("raw").path("data").path("id.orig_h").asText());
        inbox.close();
    }
    @Test void recoversCompleteTemporaryWritesAndQuarantinesPartialOnes() throws Exception {
        Files.writeString(dir.resolve("complete.tmp"), JSON.writeValueAsString(Map.of("text", "wire", "source", "s", "asset", "a", "receivedAt", "2026-10-03T00:00:00Z")));
        Files.writeString(dir.resolve("empty.tmp"), "");
        Files.writeString(dir.resolve("partial.tmp"), "{\"text\":");
        var inbox = new RouterInbox(dir);
        assertThrows(java.io.IOException.class, () -> new RouterInbox(dir));
        assertEquals("wire", inbox.poll().getOriginalText());
        assertTrue(Files.exists(dir.resolve("dead/empty.tmp")));
        assertTrue(Files.exists(dir.resolve("dead/partial.tmp")));
        inbox.close();
    }
}
