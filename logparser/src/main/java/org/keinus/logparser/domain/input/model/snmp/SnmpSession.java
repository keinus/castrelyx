package org.keinus.logparser.domain.input.model.snmp;

import java.io.Closeable;
import java.io.IOException;
import org.snmp4j.*;
import org.snmp4j.mp.*;
import org.snmp4j.security.*;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/** Each session owns its USM; configuring one adapter cannot overwrite another's users. */
final class SnmpSession implements Closeable {
    final Snmp snmp;
    final USM usm;
    final DefaultUdpTransportMapping transport;
    final OctetString engineId;

    SnmpSession(UdpAddress address, OctetString engineId, int boots) throws IOException {
        this.engineId = engineId;
        SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.maxCompatibility);
        usm = new USM(protocols, engineId, boots);
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv1());
        dispatcher.addMessageProcessingModel(new MPv2c());
        MPv3 mpv3 = new MPv3(usm);
        mpv3.setSecurityProtocols(protocols);
        dispatcher.addMessageProcessingModel(mpv3);
        transport = new DefaultUdpTransportMapping(address);
        snmp = new Snmp(dispatcher, transport);
    }

    static SnmpSession client() throws IOException {
        return new SnmpSession(new UdpAddress("0.0.0.0/0"), new OctetString(MPv3.createLocalEngineID()), 0);
    }

    void addUser(SnmpSecurity security, OctetString authoritativeEngineId) {
        if (authoritativeEngineId == null) usm.addUser(security.user());
        else usm.addUser(security.user(), authoritativeEngineId);
    }

    void listen() throws IOException { snmp.listen(); }

    static <A extends Address> void respond(CommandResponderEvent<A> event) throws IOException {
        PDU response = (PDU) event.getPDU().clone();
        response.setType(PDU.RESPONSE);
        response.setErrorStatus(PDU.noError);
        response.setErrorIndex(0);
        int status = event.getMessageDispatcher().returnResponsePdu(event.getMessageProcessingModel(),
                event.getSecurityModel(), event.getSecurityName(), event.getSecurityLevel(), response,
                event.getMaxSizeResponsePDU(), event.getStateReference(), new StatusInformation());
        if (status != SnmpConstants.SNMP_MP_OK) throw new IOException("SNMP response failed: " + status);
    }

    @Override public void close() throws IOException { snmp.close(); }
}
