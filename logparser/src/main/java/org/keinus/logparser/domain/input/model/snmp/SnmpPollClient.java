package org.keinus.logparser.domain.input.model.snmp;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.keinus.logparser.domain.input.model.snmp.SnmpInputAdapter.SnmpOid;
import org.keinus.logparser.domain.input.model.snmp.SnmpInputAdapter.SnmpTarget;

/** One reusable session per target keeps equal usernames with different device passwords independent. */
final class SnmpPollClient implements SnmpInputAdapter.SnmpClient {
    private final Map<SnmpTarget, SnmpSession> sessions = new LinkedHashMap<>();

    SnmpPollClient(List<SnmpTarget> targets) throws IOException {
        try {
            for (SnmpTarget target : targets) {
                SnmpSecurity.version(target.version());
                if (sessions.containsKey(target)) continue;
                SnmpSession session = SnmpSession.client();
                sessions.put(target, session);
                if (target.isVersion3()) session.addUser(security(target), null);
                session.listen();
            }
        } catch (Exception e) {
            close();
            throw new IOException("Cannot initialize SNMP polling sessions", e);
        }
    }

    private SnmpSecurity security(SnmpTarget target) {
        return SnmpSecurity.read(new ObjectMapper().valueToTree(target), null, true);
    }

    @Override public Map<String, Object> get(SnmpTarget target, List<SnmpOid> oids, int timeoutMs, int retries) throws IOException {
        PDU pdu = target.isVersion3() ? new ScopedPDU() : new PDU();
        pdu.setType(PDU.GET);
        for (SnmpOid oid : oids) pdu.add(new VariableBinding(new OID(oid.oid())));
        Target<UdpAddress> destination;
        if (target.isVersion3()) {
            UserTarget<UdpAddress> user = new UserTarget<>();
            user.setSecurityName(new OctetString(target.securityName()));
            user.setSecurityLevel(SnmpSecurity.level(target.securityLevel()));
            user.setSecurityModel(org.snmp4j.security.SecurityModel.SECURITY_MODEL_USM);
            destination = user;
        } else {
            CommunityTarget<UdpAddress> community = new CommunityTarget<>();
            community.setCommunity(new OctetString(target.community()));
            destination = community;
        }
        destination.setVersion(SnmpSecurity.version(target.version()));
        destination.setAddress(new UdpAddress(target.host() + "/" + target.port()));
        destination.setTimeout(timeoutMs);
        destination.setRetries(retries);
        ResponseEvent<UdpAddress> response = sessions.get(target).snmp.send(pdu, destination);
        PDU received = response == null ? null : response.getResponse();
        if (received == null) throw new IOException("No SNMP response from " + target.host());
        if (received.getType() != PDU.RESPONSE) throw new IOException("Unexpected SNMP response PDU: " + PDU.getTypeString(received.getType()));
        if (received.getErrorStatus() != PDU.noError) throw new IOException(received.getErrorStatusText());
        Map<OID, VariableBinding> bindings = new LinkedHashMap<>();
        for (VariableBinding binding : received.getVariableBindings()) bindings.put(binding.getOid(), binding);
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (SnmpOid oid : oids) {
            VariableBinding binding = bindings.get(new OID(oid.oid()));
            if (binding == null) throw new IOException("Missing SNMP response OID: " + oid.oid());
            metrics.put(oid.name(), SnmpValues.binding(binding));
        }
        return metrics;
    }

    @Override public void close() throws IOException {
        IOException error = null;
        for (SnmpSession session : sessions.values()) {
            try { session.close(); } catch (IOException e) { error = e; }
        }
        if (error != null) throw error;
    }
}
