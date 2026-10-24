package org.keinus.logparser.domain.input.model.snmp;

import static org.junit.jupiter.api.Assertions.*;
import java.net.DatagramSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.input.service.InputFactory;
import org.keinus.logparser.domain.model.LogEvent;
import org.snmp4j.*;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;

class SnmpTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER = "test-poller";
    private static final String AUTH = "test-auth-passphrase";
    private static final String PRIV = "test-priv-passphrase";
    private static final OID VALUE_OID = new OID("1.3.6.1.2.1.2.2.1.10.1");
    @TempDir Path temporary;

    @ParameterizedTest
    @CsvSource({"2c,trap,noAuthNoPriv", "2c,inform,noAuthNoPriv", "3,trap,noAuthNoPriv",
            "3,inform,noAuthNoPriv", "3,trap,authNoPriv", "3,inform,authNoPriv", "3,trap,authPriv", "3,inform,authPriv"})
    void receivesNotificationsOverUdp(String version, String operation, String level) throws Exception {
        try (SnmpSession sender = SnmpSession.client()) {
            JsonNode user = user(level, "SHA256");
            sender.addUser(SnmpSecurity.read(user, null, true), null);
            sender.listen();
            try (SnmpNotificationInputAdapter receiver = receiver(sender, user, 10)) {
                PDU pdu = notification(version, operation, 91);
                var response = sender.snmp.send(pdu, target(version, receiver.listenAddress(), level));
                if (operation.equals("inform")) {
                    assertNotNull(response.getResponse());
                    assertEquals(PDU.RESPONSE, response.getResponse().getType());
                    assertEquals(91, response.getResponse().getRequestID().getValue());
                }
                LogEvent event = awaitEvent(receiver);
                assertEquals("127.0.0.1", event.getSourceHost());
                assertEquals("snmp-notification", event.getMessageType());
                JsonNode payload = JSON.readTree(event.getOriginalText());
                assertEquals(operation, payload.path("operation").asText());
                assertEquals(version, payload.path("version").asText());
                if (version.equals("3")) {
                    assertEquals(sender.engineId.toHexString(), payload.path("context_engine_id").asText());
                    if (operation.equals("inform")) {
                        assertEquals(receiver.localEngineId().toHexString(), payload.path("engine_id").asText());
                    }
                }
                assertEquals("1.3.6.1.6.3.1.1.5.3", payload.path("trap_oid").asText());
                assertEquals("18446744073709551615", payload.path("varbinds").get(2).path("value").asText());
                assertFalse(event.getOriginalText().contains(AUTH));
                assertFalse(event.getOriginalText().contains("test-community"));
                if (operation.equals("inform")) {
                    assertNotNull(sender.snmp.send(pdu, target(version, receiver.listenAddress(), level)).getResponse());
                    assertNull(receiver.run(), "Retransmitted Inform must only produce one event");
                }
            }
        }
    }

    @Test void receivesV1EnterpriseTrap() throws Exception {
        try (SnmpSession sender = SnmpSession.client();
             SnmpNotificationInputAdapter receiver = receiver(sender, user("authPriv", "SHA256"), 10)) {
            sender.listen();
            PDUv1 trap = new PDUv1();
            trap.setType(PDU.V1TRAP);
            trap.setEnterprise(new OID("1.3.6.1.4.1.9999"));
            trap.setGenericTrap(PDUv1.ENTERPRISE_SPECIFIC);
            trap.setSpecificTrap(42);
            trap.setAgentAddress(new IpAddress("192.0.2.1"));
            trap.setTimestamp(100);
            sender.snmp.send(trap, target("1", receiver.listenAddress(), "noAuthNoPriv"));
            JsonNode payload = JSON.readTree(awaitEvent(receiver).getOriginalText());
            assertEquals("1.3.6.1.4.1.9999.0.42", payload.path("trap_oid").asText());
            assertEquals("192.0.2.1", payload.path("agent_address").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2c", "3"})
    void fullQueueDoesNotAcknowledgeOrRememberUnacceptedInform(String version) throws Exception {
        try (SnmpSession sender = SnmpSession.client();
             SnmpNotificationInputAdapter receiver = receiver(sender, user("authPriv", "SHA256"), 1)) {
            sender.addUser(SnmpSecurity.read(user("authPriv", "SHA256"), null, true), null);
            sender.listen();
            Target<UdpAddress> target = target(version, receiver.listenAddress(), "authPriv");
            assertNotNull(sender.snmp.send(notification(version, "inform", 1), target).getResponse());
            PDU second = notification(version, "inform", 2);
            // Only the intentional no-ACK check needs a short observation window.
            // Positive sends keep the normal UDP/USM response budget, with no retries.
            target.setTimeout(200);
            assertNull(sender.snmp.send(second, target).getResponse());
            assertNotNull(receiver.run());
            target.setTimeout(1500);
            PDU response = sender.snmp.send(second, target).getResponse();
            assertNotNull(response);
            assertEquals(PDU.RESPONSE, response.getType());
            assertEquals(2, response.getRequestID().getValue());
            assertEquals(2, JSON.readTree(awaitEvent(receiver).getOriginalText()).path("request_id").asInt());
            assertNull(receiver.run(), "A recovered Inform must only enqueue one event");
        }
    }

    @Test void rejectsWrongCommunityWrongPasswordAndSecurityDowngrade() throws Exception {
        try (SnmpSession sender = SnmpSession.client();
             SnmpNotificationInputAdapter receiver = receiver(sender, user("authPriv", "SHA256"), 10)) {
            sender.addUser(SnmpSecurity.read(user("authPriv", "SHA256"), null, true), null);
            sender.listen();
            CommunityTarget<UdpAddress> wrong = (CommunityTarget<UdpAddress>) target("2c", receiver.listenAddress(), "noAuthNoPriv");
            wrong.setCommunity(new OctetString("wrong-community"));
            wrong.setTimeout(200);
            assertNull(sender.snmp.send(notification("2c", "inform", 5), wrong).getResponse());
            Target<UdpAddress> downgrade = target("3", receiver.listenAddress(), "noAuthNoPriv");
            downgrade.setTimeout(300);
            var response = sender.snmp.send(notification("3", "inform", 6), downgrade).getResponse();
            assertTrue(response == null || response.getType() != PDU.RESPONSE);
            assertNull(receiver.run());
        }
        try (SnmpSession wrongSender = SnmpSession.client();
             SnmpNotificationInputAdapter receiver = receiver(wrongSender, user("authPriv", "SHA256"), 10)) {
            JsonNode wrongUser = user("authPriv", "SHA256");
            ((com.fasterxml.jackson.databind.node.ObjectNode) wrongUser).put("authPassphrase", "wrong-auth-passphrase");
            wrongSender.addUser(SnmpSecurity.read(wrongUser, null, true), null);
            wrongSender.listen();
            Target<UdpAddress> target = target("3", receiver.listenAddress(), "authPriv");
            target.setTimeout(300);
            var response = wrongSender.snmp.send(notification("3", "inform", 7), target).getResponse();
            assertTrue(response == null || response.getType() != PDU.RESPONSE);
            assertNull(receiver.run());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2c", "3"})
    void pollsThroughFactoryAndPreservesValuesAndOidErrors(String version) throws Exception {
        try (SnmpSession agent = SnmpSession.client()) {
            agent.addUser(SnmpSecurity.read(user("authPriv", "SHA256"), null, true), null);
            agent.snmp.addCommandResponder(new CommandResponder() {
                @Override public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
                    if (event.getPDU().getType() != PDU.GET) return;
                    event.getPDU().set(0, new VariableBinding(VALUE_OID, new Counter32(12345)));
                    if (!version.equals("1")) event.getPDU().set(1, new VariableBinding(new OID("1.3.6.1.2.1.1.99.0"), Null.noSuchInstance));
                    try { SnmpSession.respond(event); event.setProcessed(true); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }
            });
            agent.listen();
            var target = JSON.createObjectNode().put("host", "127.0.0.1").put("port", agent.transport.getListenAddress().getPort())
                    .put("version", version).put("community", "test-community");
            target.setAll((com.fasterxml.jackson.databind.node.ObjectNode) user("authPriv", "SHA256"));
            InputAdapterConfig config = new InputAdapterConfig();
            config.setType("snmp"); config.setMessagetype("snmp-poll"); config.setTimeoutMs(1000);
            var oids = JSON.createArrayNode().add(JSON.valueToTree(Map.of("name", "ifInOctets", "oid", VALUE_OID.toDottedString())));
            if (!version.equals("1")) oids.add(JSON.valueToTree(Map.of("name", "missing", "oid", "1.3.6.1.2.1.1.99.0")));
            config.setConfigParams(JSON.writeValueAsString(Map.of("targets", List.of(target), "oids", oids)));
            try (var adapter = InputFactory.getInputAdapter(config)) {
                assertInstanceOf(SnmpInputAdapter.class, adapter);
                JsonNode payload = JSON.readTree(adapter.run().getOriginalText());
                assertEquals(12345, payload.path("metrics").path("ifInOctets").path("value").asLong());
                assertEquals(version.equals("1") ? "success" : "partial", payload.path("poll_status").asText());
                if (!version.equals("1")) assertEquals("noSuchInstance", payload.path("metrics").path("missing").path("status").asText());
            }
        }
    }

    @Test void keepsEngineIdentityAndIncrementsBootsOnRestart() throws Exception {
        Path file = temporary.resolve("persistent-engine.json");
        OctetString id;
        int boots;
        try (SnmpEngineState first = new SnmpEngineState(file, null)) {
            id = first.engineId; boots = first.boots;
            assertThrows(java.io.IOException.class, () -> new SnmpEngineState(file, null));
        }
        try (SnmpEngineState second = new SnmpEngineState(file, null)) {
            assertEquals(id, second.engineId);
            assertEquals(boots + 1, second.boots);
        }
        java.nio.file.Files.writeString(file, "{}");
        assertThrows(java.io.IOException.class, () -> new SnmpEngineState(file, null));
    }

    @Test void supportsLegacyAuthProtocolsExplicitly() throws Exception {
        try (SnmpSession session = SnmpSession.client()) {
            assertNotNull(session.usm.getSecurityProtocols().getAuthenticationProtocol(SnmpSecurity.authProtocol("MD5")));
            assertNotNull(session.usm.getSecurityProtocols().getAuthenticationProtocol(SnmpSecurity.authProtocol("SHA1")));
        }
    }

    @Test void receivesAuthenticatedInformAfterReceiverRestart() throws Exception {
        int port;
        try (DatagramSocket socket = new DatagramSocket(0)) { port = socket.getLocalPort(); }
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("SnmpNotificationInputAdapter"); config.setMessagetype("notifications");
        config.setHost("127.0.0.1"); config.setPort(port);
        config.setConfigParams(JSON.writeValueAsString(Map.of("users", List.of(user("authPriv", "SHA256")),
                "engineStatePath", temporary.resolve("restart.json").toString())));
        try (SnmpSession sender = SnmpSession.client()) {
            sender.addUser(SnmpSecurity.read(user("authPriv", "SHA256"), null, true), null);
            sender.listen();
            var target = target("3", new UdpAddress("127.0.0.1/" + port), "authPriv");
            target.setRetries(1);
            OctetString id;
            try (var receiver = new SnmpNotificationInputAdapter(config)) {
                id = receiver.localEngineId();
                assertNotNull(sender.snmp.send(notification("3", "inform", 101), target).getResponse());
                assertNotNull(receiver.run());
            }
            try (var receiver = new SnmpNotificationInputAdapter(config)) {
                assertEquals(id, receiver.localEngineId());
                var response = sender.snmp.send(notification("3", "inform", 102), target).getResponse();
                assertNotNull(response);
                assertEquals(PDU.RESPONSE, response.getType());
                assertNotNull(receiver.run());
            }
        }
    }

    private SnmpNotificationInputAdapter receiver(SnmpSession sender, JsonNode user, int capacity) throws Exception {
        int port;
        try (DatagramSocket socket = new DatagramSocket(0)) { port = socket.getLocalPort(); }
        var remoteUser = user.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) remoteUser).put("engineId", sender.engineId.toHexString());
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("snmp_notification"); config.setMessagetype("snmp-notification");
        config.setHost("127.0.0.1"); config.setPort(port); config.setQueueSize(capacity);
        config.setConfigParams(JSON.writeValueAsString(Map.of("communities", List.of("test-community"),
                "users", List.of(user, remoteUser), "engineStatePath", temporary.resolve("receiver-" + port + ".json").toString())));
        return (SnmpNotificationInputAdapter) InputFactory.getInputAdapter(config);
    }

    private static JsonNode user(String level, String auth) {
        return JSON.valueToTree(Map.of("securityName", USER, "securityLevel", level, "authProtocol", auth,
                "authPassphrase", AUTH, "privProtocol", "AES128", "privPassphrase", PRIV));
    }

    private static Target<UdpAddress> target(String version, UdpAddress address, String level) {
        Target<UdpAddress> target;
        if (version.equals("3")) {
            UserTarget<UdpAddress> user = new UserTarget<>();
            user.setSecurityName(new OctetString(USER)); user.setSecurityLevel(SnmpSecurity.level(level));
            target = user;
        } else {
            CommunityTarget<UdpAddress> community = new CommunityTarget<>();
            community.setCommunity(new OctetString("test-community")); target = community;
        }
        target.setVersion(SnmpSecurity.version(version)); target.setAddress(address); target.setTimeout(1500); target.setRetries(0);
        return target;
    }

    private static PDU notification(String version, String operation, int requestId) {
        PDU pdu = version.equals("3") ? new ScopedPDU() : new PDU();
        pdu.setType(operation.equals("inform") ? PDU.INFORM : PDU.TRAP);
        pdu.setRequestID(new Integer32(requestId));
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(12345)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID("1.3.6.1.6.3.1.1.5.3")));
        pdu.add(new VariableBinding(VALUE_OID, new Counter64(-1L)));
        return pdu;
    }

    private static LogEvent awaitEvent(SnmpNotificationInputAdapter receiver) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        LogEvent event;
        while ((event = receiver.run()) == null && System.nanoTime() < deadline) Thread.sleep(10);
        assertNotNull(event, "Expected a decoded notification over UDP");
        return event;
    }
}
