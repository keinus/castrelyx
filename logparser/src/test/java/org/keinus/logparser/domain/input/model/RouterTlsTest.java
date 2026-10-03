package org.keinus.logparser.domain.input.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocket;
import static org.junit.jupiter.api.Assertions.*;

class RouterTlsTest {
    @TempDir Path dir;
    private final ObjectMapper json=new ObjectMapper();
    @Test void receivesByteCountedRouterMessagesWithAuthenticatedCertificateBinding() throws Exception {
        Path server=dir.resolve("server.p12"), client=dir.resolve("client.p12"), trust=dir.resolve("trust.p12"), cert=dir.resolve("client.crt");
        keytool("-genkeypair","-alias","key","-keyalg","EC","-dname","CN=localhost","-validity","2","-keystore",server.toString());
        keytool("-genkeypair","-alias","key","-keyalg","EC","-dname","CN=router-client","-validity","2","-keystore",client.toString());
        keytool("-exportcert","-alias","key","-keystore",client.toString(),"-file",cert.toString());
        keytool("-importcert","-noprompt","-alias","client","-keystore",trust.toString(),"-file",cert.toString());
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var config=new InputAdapterConfig();config.setType("TlsTcpInputAdapter");config.setPort(port);config.setMessagetype("security-router");
        config.setConfigParams(json.writeValueAsString(Map.of("keyStorePath",server.toString(),"keyStorePassword","testpass",
            "trustStorePath",trust.toString(),"trustStorePassword","testpass","clientAuth","need",
            "framing","octet-counted","inboxDirectory",dir.resolve("inbox").toString(),
            "clientSourceBindings",Map.of("router-client",Map.of("sourceId","ssw-1","assetUid","asset-1")))));
        var ssl=TlsConfigSupport.createClientSslContext(json.valueToTree(Map.of("keyStorePath",client.toString(),"keyStorePassword","testpass",
            "trustStorePath",server.toString(),"trustStorePassword","testpass")),"test");
        AtomicBoolean running=new AtomicBoolean(true);AtomicReference<LogEvent> received=new AtomicReference<>();
        try(var adapter=new TlsTcpInputAdapter(config)) {
            Thread worker=Thread.ofVirtual().start(()->{while(running.get()){var event=adapter.run();if(event!=null)received.set(event);}});
            try(var socket=(SSLSocket)ssl.getSocketFactory().createSocket("127.0.0.1",port)) {
                socket.startHandshake();
                byte[] body=RouterPipelineTest.wire("ssw-1","audit","{\"id\":\"tls-1\",\"at\":\"2026-10-03T00:00:00Z\",\"kind\":\"job.committed\",\"detail\":\"한글\"}").getBytes(StandardCharsets.UTF_8);
                var output=socket.getOutputStream();output.write((body.length+" ").getBytes(StandardCharsets.US_ASCII));
                output.write(body,0,7);output.flush();output.write(body,7,body.length-7);output.flush();
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(received.get()==null && System.nanoTime()<until)Thread.sleep(20);
                assertNotNull(received.get());assertEquals("ssw-1",received.get().getAuthenticatedSourceId());
                assertEquals("asset-1",received.get().getAuthenticatedAssetUid());RouterPipelineTest.parse(received.get());
                received.get().acknowledgeStored();
            } finally {running.set(false);worker.interrupt();worker.join(2000);}
        }
        // Same trusted certificate is rejected when it has no registered CN.
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(config.getConfigParams());
        root.set("clientSourceBindings",json.valueToTree(Map.of("different-client",Map.of("sourceId","ssw-1","assetUid","asset-1"))));
        config.setConfigParams(root.toString());
        try(var adapter=new TlsTcpInputAdapter(config);var socket=(SSLSocket)ssl.getSocketFactory().createSocket("127.0.0.1",port)) {
            adapter.run();socket.startHandshake();socket.setSoTimeout(2000);
            assertEquals(-1,socket.getInputStream().read());assertNull(adapter.run());
        }
    }
    private void keytool(String... args) throws Exception {
        List<String> command=new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString());
        command.addAll(List.of(args));command.addAll(List.of("-storepass","testpass","-storetype","PKCS12"));
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.waitFor(),output);
    }
}
