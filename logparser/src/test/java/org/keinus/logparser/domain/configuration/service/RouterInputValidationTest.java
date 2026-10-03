package org.keinus.logparser.domain.configuration.service;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.input.model.TcpInputSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RouterInputValidationTest {
    @Test void genericTcpRetainsLineDefault() throws Exception {
        var config = new InputAdapterConfig();
        config.setType("TcpInputAdapter");
        assertEquals("line", TcpInputSettings.from(config).framing());
    }
    @Test void routerRequiresTlsFramingAndRegisteredIdentity() throws Exception {
        var config = new InputAdapterConfig();
        config.setType("TlsTcpInputAdapter"); config.setMessagetype("security-router");
        var json = new ObjectMapper();
        var options = json.valueToTree(Map.of("framing", "octet-counted", "clientAuth", "need",
            "inboxDirectory", Path.of("inbox").toAbsolutePath().toString(),
            "clientSourceBindings", Map.of("cert-cn", Map.of("sourceId", "ssw-1", "assetUid", "asset-1"))));
        config.setConfigParams(options.toString());
        assertEquals("asset-1", TcpInputSettings.from(config).bindings().path("cert-cn").path("assetUid").asText());
        config.setType("TcpInputAdapter");
        assertThrows(IOException.class, () -> TcpInputSettings.from(config));
        config.setType("TlsTcpInputAdapter");
        ((com.fasterxml.jackson.databind.node.ObjectNode)options).put("framing", "line");
        config.setConfigParams(options.toString());
        assertThrows(IOException.class, () -> TcpInputSettings.from(config));
        ((com.fasterxml.jackson.databind.node.ObjectNode)options).put("framing", "octet-counted");
        ((com.fasterxml.jackson.databind.node.ObjectNode)options.path("clientSourceBindings")).removeAll();
        config.setConfigParams(options.toString());
        assertThrows(IOException.class, () -> TcpInputSettings.from(config));
    }
}
