package org.keinus.logparser.domain.input.model;

import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;

import java.io.IOException;

public class TlsRabbitMqInputAdapter extends RabbitMqInputAdapter {
    public TlsRabbitMqInputAdapter(InputAdapterConfig config) throws IOException {
        super(config);
    }

    TlsRabbitMqInputAdapter(InputAdapterConfig config, RabbitMqClient client) throws IOException {
        super(config, client);
    }
}
