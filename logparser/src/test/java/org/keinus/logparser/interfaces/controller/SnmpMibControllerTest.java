package org.keinus.logparser.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.input.model.snmp.SnmpMibParser;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SnmpMibControllerTest {
    @Test void returnsReadableOidsAndActionableImportErrorsThroughHttp() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SnmpMibController(new SnmpMibParser())).build();
        String content;
        try (var stream = getClass().getResourceAsStream("/snmp/EXAMPLE-MIB.mib")) {
            content = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        mvc.perform(post("/api/v1/snmp/mib/preview").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(Map.of("files", List.of(Map.of("name", "example.mib", "content", content))))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.objects[0].oid").value("1.3.6.1.4.1.32473.1"))
                .andExpect(jsonPath("$.objects[1].scalar").value(false))
                .andExpect(jsonPath("$.objects[1].indexes[0]").value("portIndex"));
        mvc.perform(post("/api/v1/snmp/mib/preview").contentType("application/json").content("{\"files\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1 and 16")));
    }
}
