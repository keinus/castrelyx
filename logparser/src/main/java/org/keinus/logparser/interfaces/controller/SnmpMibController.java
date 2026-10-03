package org.keinus.logparser.interfaces.controller;

import org.keinus.logparser.domain.input.model.snmp.SnmpMibParser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/snmp/mib")
public class SnmpMibController {
    private final SnmpMibParser parser;
    public SnmpMibController(SnmpMibParser parser) { this.parser = parser; }

    public record ImportRequest(List<SnmpMibParser.MibFile> files) {}

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody ImportRequest request) {
        try {
            return ResponseEntity.ok(parser.parse(request.files()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
