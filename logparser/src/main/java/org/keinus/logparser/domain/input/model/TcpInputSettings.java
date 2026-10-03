package org.keinus.logparser.domain.input.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

public record TcpInputSettings(String framing, int maxFrameBytes, JsonNode bindings, String inboxDirectory) {
    public static TcpInputSettings from(InputAdapterConfig config) throws IOException {
        var mapper = new ObjectMapper();
        JsonNode root = config.getConfigParams()==null || config.getConfigParams().isBlank() ? mapper.createObjectNode() : mapper.readTree(config.getConfigParams());
        if (!root.isObject()) throw new IOException("TCP configParams must be an object");
        String framing = root.path("framing").asText("line");
        int maximum = root.path("maxFrameBytes").asInt(2<<20);
        if (!Set.of("line","octet-counted").contains(framing)) throw new IOException("framing must be line or octet-counted");
        if (maximum<1024 || maximum>2<<20) throw new IOException("maxFrameBytes must be 1024..2097152");
        JsonNode bindings = root.path("clientSourceBindings");
        String directory = root.path("inboxDirectory").asText("");
        if ("security-router".equals(config.getMessagetype())) {
            if (!Set.of("TlsTcpInputAdapter","tls_tcp","tlstcp").contains(config.getType()) || !"need".equals(root.path("clientAuth").asText())) throw new IOException("security-router requires TLS and clientAuth=need");
            if (!framing.equals("octet-counted")) throw new IOException("security-router requires octet-counted framing");
            if (directory.isBlank() || !Path.of(directory).isAbsolute()) throw new IOException("security-router requires an absolute inboxDirectory");
            if (!bindings.isObject() || bindings.isEmpty()) throw new IOException("security-router requires registered clientSourceBindings");
            Set<String> sourceIds = new java.util.HashSet<>();
            var entries = bindings.fields();
            while (entries.hasNext()) {
                var entry=entries.next(); var binding=entry.getValue();
                if (entry.getKey().isBlank() || !binding.path("sourceId").asText().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}") || !binding.path("assetUid").asText().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}")) throw new IOException("Invalid client source/asset binding");
                if (!sourceIds.add(binding.path("sourceId").asText())) throw new IOException("sourceId must have one registered certificate CN");
            }
        }
        return new TcpInputSettings(framing,maximum,bindings,directory);
    }
}
