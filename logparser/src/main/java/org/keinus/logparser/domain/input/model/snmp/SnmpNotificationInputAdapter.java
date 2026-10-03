package org.keinus.logparser.domain.input.model.snmp;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.input.model.InputAdapter;
import org.keinus.logparser.domain.model.LogEvent;
import org.snmp4j.*;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import lombok.extern.slf4j.Slf4j;

/** UDP v1/v2c/v3 Trap and v2c/v3 Inform receiver. Inform ACK means memory queue acceptance. */
@Slf4j
public final class SnmpNotificationInputAdapter extends InputAdapter implements CommandResponder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ReceiverConfig settings;
    private final SnmpSession session;
    private final SnmpEngineState engineState;
    private final BlockingQueue<LogEvent> queue;
    private final Set<String> allowedHosts;
    private final LinkedHashMap<String, Long> informs = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SnmpNotificationInputAdapter(InputAdapterConfig config) throws IOException {
        super(config);
        settings = ReceiverConfig.read(config, true);
        queue = new ArrayBlockingQueue<>(settings.queueSize());
        allowedHosts = new HashSet<>();
        for (String host : settings.allowedHosts()) allowedHosts.add(InetAddress.getByName(host).getHostAddress());
        SnmpEngineState state = null;
        SnmpSession opened = null;
        try {
            if (!settings.users().isEmpty()) state = new SnmpEngineState(settings.statePath(), settings.engineId());
            opened = new SnmpSession(new UdpAddress(settings.host() + "/" + settings.port()),
                    state == null ? new OctetString(MPv3.createLocalEngineID()) : state.engineId,
                    state == null ? 0 : state.boots);
            Set<String> users = new HashSet<>();
            for (ReceiverUser user : settings.users()) {
                OctetString engineId = user.engineId() == null ? opened.engineId : user.engineId();
                if (!users.add(user.security().name() + ":" + engineId.toHexString())) {
                    throw new IllegalArgumentException("Duplicate SNMP user for the local engine ID");
                }
                opened.addUser(user.security(), engineId);
            }
            session = opened;
            engineState = state;
            session.snmp.addCommandResponder(this);
            session.listen();
            log.info("SNMP notification listener started at {} (Inform ACK: memory queue acceptance)",
                    session.transport.getListenAddress());
        } catch (Exception e) {
            try { if (opened != null) opened.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
            try { if (state != null) state.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
            throw new IOException("Cannot start SNMP notification listener", e);
        }
    }

    public static void validateConfig(InputAdapterConfig config) throws IOException {
        ReceiverConfig.read(config, false);
    }

    @Override public LogEvent run() { return queue.poll(); }

    @Override public synchronized <A extends Address> void processPdu(CommandResponderEvent<A> event) {
        PDU pdu = event.getPDU();
        if (closed.get() || pdu == null || !(event.getPeerAddress() instanceof UdpAddress peer)) return;
        if (pdu.getType() != PDU.V1TRAP && pdu.getType() != PDU.TRAP && pdu.getType() != PDU.INFORM) return;
        event.setProcessed(true);
        String host = peer.getInetAddress().getHostAddress();
        try {
            if ((!allowedHosts.isEmpty() && !allowedHosts.contains(host)) || !authorized(event)) return;
            if (pdu.size() > settings.maxVarBindings()) {
                log.warn("SNMP notification rejected: too many variable bindings from {}", host);
                return;
            }
            boolean inform = pdu.getType() == PDU.INFORM;
            String key = null;
            long now = System.nanoTime();
            if (inform) {
                var expired = informs.entrySet().iterator();
                while (expired.hasNext()) {
                    if (now - expired.next().getValue() < settings.dedupTtlMs() * 1_000_000L) break;
                    expired.remove();
                }
                key = fingerprint(event);
                if (informs.containsKey(key)) {
                    SnmpSession.respond(event);
                    return;
                }
            }
            LogEvent logEvent = createLogEvent(JSON.writeValueAsString(payload(event, peer)), host);
            if (!queue.offer(logEvent)) {
                log.warn("SNMP notification queue full; {} from {} was not accepted", inform ? "Inform" : "Trap", host);
                return; // Never acknowledge an Inform that was not accepted.
            }
            if (inform) {
                informs.put(key, now);
                if (informs.size() > settings.dedupCacheSize()) informs.remove(informs.keySet().iterator().next());
                SnmpSession.respond(event);
            }
        } catch (Exception e) {
            log.warn("SNMP notification processing failed from {}: {}", host, e.getMessage());
        } finally {
            if (pdu.getType() == PDU.INFORM && event.getStateReference() != null) {
                event.getMessageDispatcher().releaseStateReference(event.getMessageProcessingModel(), event.getPduHandle());
            }
        }
    }

    private boolean authorized(CommandResponderEvent<?> event) {
        String name = new OctetString(event.getSecurityName()).toString();
        if (event.getMessageProcessingModel() == SnmpConstants.version3) {
            // USM has already checked engine-scoped keys; enforce the configured minimum level as well.
            return settings.users().stream().anyMatch(user -> user.security().name().equals(name)
                    && event.getSecurityLevel() >= user.security().level());
        }
        return settings.communities().contains(name);
    }

    private Map<String, Object> payload(CommandResponderEvent<?> event, UdpAddress peer) {
        PDU pdu = event.getPDU();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("protocol", "snmp");
        payload.put("operation", pdu.getType() == PDU.INFORM ? "inform" : "trap");
        payload.put("received_at", Instant.now().toString());
        payload.put("observed_at", payload.get("received_at"));
        payload.put("target_host", peer.getInetAddress().getHostAddress());
        payload.put("source_port", peer.getPort());
        payload.put("version", switch (event.getMessageProcessingModel()) { case 0 -> "1"; case 1 -> "2c"; default -> "3"; });
        payload.put("request_id", pdu.getRequestID().getValue());
        if (event.getMessageProcessingModel() == SnmpConstants.version3) {
            payload.put("security_name", new OctetString(event.getSecurityName()).toString());
            payload.put("security_level", switch (event.getSecurityLevel()) {
                case 1 -> "noAuthNoPriv"; case 2 -> "authNoPriv"; default -> "authPriv";
            });
            // SNMP4J exposes the authoritative engine in response state for Inform only.
            // A Trap's context engine is retained below; it need not equal its authoritative engine.
            if (event.getStateReference() != null && event.getStateReference().getSecurityEngineID() != null) {
                payload.put("engine_id", new OctetString(event.getStateReference().getSecurityEngineID()).toHexString());
            }
        }
        if (pdu instanceof ScopedPDU scoped) {
            payload.put("context_name", scoped.getContextName().toString());
            payload.put("context_engine_id", scoped.getContextEngineID().toHexString());
        }
        if (pdu instanceof PDUv1 v1) {
            payload.put("enterprise_oid", v1.getEnterprise().toDottedString());
            payload.put("agent_address", v1.getAgentAddress().toString());
            payload.put("generic_trap", v1.getGenericTrap());
            payload.put("specific_trap", v1.getSpecificTrap());
            payload.put("uptime_ticks", v1.getTimestamp());
            payload.put("trap_oid", v1.getGenericTrap() == PDUv1.ENTERPRISE_SPECIFIC
                    ? v1.getEnterprise().toDottedString() + ".0." + v1.getSpecificTrap()
                    : "1.3.6.1.6.3.1.1.5." + (v1.getGenericTrap() + 1));
        }
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (VariableBinding binding : pdu.getVariableBindings()) {
            bindings.add(SnmpValues.binding(binding));
            if (binding.getOid().equals(SnmpConstants.snmpTrapOID)) payload.put("trap_oid", binding.getVariable().toString());
            if (binding.getOid().equals(SnmpConstants.sysUpTime)) payload.put("uptime_ticks", binding.getVariable().toLong());
        }
        payload.put("varbinds", bindings);
        return payload;
    }

    private String fingerprint(CommandResponderEvent<?> event) throws Exception {
        String identity = event.getPeerAddress() + "|" + event.getMessageProcessingModel() + "|"
                + new OctetString(event.getSecurityName()).toHexString() + "|" + event.getSecurityLevel() + "|" + event.getPDU();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
    }

    UdpAddress listenAddress() { return session.transport.getListenAddress(); }
    OctetString localEngineId() { return session.engineId; }

    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try { session.close(); } finally {
            synchronized (this) { queue.clear(); informs.clear(); }
            if (engineState != null) engineState.close();
        }
    }

    private record ReceiverUser(SnmpSecurity security, OctetString engineId) {}
    private record ReceiverConfig(String host, int port, int queueSize, int maxVarBindings,
                                  long dedupTtlMs, int dedupCacheSize, Set<String> communities,
                                  List<String> allowedHosts, List<ReceiverUser> users, Path statePath, String engineId) {
        static ReceiverConfig read(InputAdapterConfig config, boolean resolveSecrets) throws IOException {
            if (config.getConfigParams() == null || config.getConfigParams().isBlank()) {
                throw new IllegalArgumentException("SnmpNotificationInputAdapter requires configParams");
            }
            JsonNode root = JSON.readTree(config.getConfigParams());
            if (root == null || !root.isObject()) throw new IllegalArgumentException("SNMP configParams must be an object");
            int port = config.getPort() == null ? 162 : config.getPort();
            if (port < 1 || port > 65535) throw new IllegalArgumentException("SNMP listen port must be 1..65535");
            int queueSize = config.getQueueSize() == null ? positive(root, "queueSize", 1000) : config.getQueueSize();
            if (queueSize < 1) throw new IllegalArgumentException("SNMP queueSize must be positive");
            Set<String> communities = new HashSet<>(strings(root, "communities"));
            for (String env : strings(root, "communityEnvs")) {
                String value = resolveSecrets ? System.getenv(env) : env;
                if (value == null || value.isBlank()) throw new IllegalArgumentException("SNMP community environment variable is missing: " + env);
                communities.add(value);
            }
            List<ReceiverUser> users = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            Map<String, Integer> levels = new HashMap<>();
            if (root.has("users") && !root.get("users").isArray()) throw new IllegalArgumentException("SNMP users must be an array");
            for (JsonNode node : root.path("users")) {
                SnmpSecurity security = SnmpSecurity.read(node, root, resolveSecrets);
                String engine = SnmpSecurity.text(node, "engineId", null);
                OctetString engineId = engine == null ? null : SnmpSecurity.engineId(engine);
                String identity = security.name() + ":" + (engineId == null ? "local" : engineId.toHexString());
                if (!identities.add(identity)) throw new IllegalArgumentException("Duplicate SNMP user/engineId");
                Integer level = levels.putIfAbsent(security.name(), security.level());
                if (level != null && level != security.level()) throw new IllegalArgumentException("SNMP users with the same securityName must have the same securityLevel");
                users.add(new ReceiverUser(security, engineId));
            }
            if (communities.isEmpty() && users.isEmpty()) throw new IllegalArgumentException("Configure SNMP users or allowed communities/communityEnvs");
            String engineId = SnmpSecurity.text(root, "engineId", null);
            if (engineId != null) SnmpSecurity.engineId(engineId);
            String stateFile = SnmpSecurity.text(root, "engineStatePath", Path.of(System.getProperty("user.home"),
                    "logparser", "data", "snmp", "receiver-" + (config.getId() == null ? port : config.getId()) + ".json").toString());
            return new ReceiverConfig(config.getHost() == null ? "0.0.0.0" : config.getHost(), port, queueSize,
                    positive(root, "maxVarBindings", 256), positive(root, "dedupTtlMs", 60000),
                    positive(root, "dedupCacheSize", 10000), communities, strings(root, "allowedHosts"), users,
                    Path.of(stateFile), engineId);
        }

        private static int positive(JsonNode root, String field, int fallback) {
            if (!root.has(field)) return fallback;
            JsonNode value = root.get(field);
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) {
                throw new IllegalArgumentException("SNMP " + field + " must be a positive integer");
            }
            return value.asInt();
        }

        private static List<String> strings(JsonNode root, String field) {
            if (!root.has(field)) return List.of();
            if (!root.get(field).isArray()) throw new IllegalArgumentException("SNMP " + field + " must be an array");
            List<String> values = new ArrayList<>();
            for (JsonNode node : root.get(field)) {
                if (!node.isTextual() || node.asText().isBlank()) throw new IllegalArgumentException("SNMP " + field + " entries must be nonblank strings");
                values.add(node.asText());
            }
            return values;
        }
    }
}
