package org.keinus.logparser.domain.input.model;

import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;

import java.io.IOException;

public class TlsTcpInputAdapter extends TcpInputAdapter {
    public TlsTcpInputAdapter(InputAdapterConfig config) throws IOException {
        super(
                config,
                (adapterConfig, port) -> TlsConfigSupport.createServerSocket(adapterConfig, port, "TlsTcpInputAdapter"),
                "TCP TLS"
        );
    }
}
