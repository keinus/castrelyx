package org.keinus.logparser.interfaces.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.keinus.logparser.domain.configuration.service.ConfigManagementService;
import org.keinus.logparser.domain.configuration.service.ConfigValidationService;
import org.keinus.logparser.domain.parse.service.ParseService;
import org.keinus.logparser.infrastructure.persistence.entity.ParserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/parsers")
@RequiredArgsConstructor
@Slf4j
public class ParserController {

    private final ConfigManagementService configManagementService;
    private final ConfigValidationService validationService;
    private final ParseService parseService;

    @GetMapping
    public ResponseEntity<Page<ParserEntity>> getAllParsers(Pageable pageable) {

        Page<ParserEntity> result = configManagementService.getAllParsers(pageable);
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<ParserEntity> createParser(@RequestBody ParserEntity entity) {

        ParserEntity created = configManagementService.createParser(entity);
        return ResponseEntity.ok(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ParserEntity> getParser(@PathVariable Long id) {

        ParserEntity entity = configManagementService.getParser(id);
        return ResponseEntity.ok(entity);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ParserEntity> updateParser(
            @PathVariable Long id,
            @RequestBody ParserEntity entity) {

        ParserEntity updated = configManagementService.updateParser(id, entity);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteParser(@PathVariable Long id) {

        configManagementService.deleteParser(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<List<ParserEntity>> getParsersByType(@PathVariable String type) {

        List<ParserEntity> result = configManagementService.getParsersByType(type);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/messagetype/{messageType}")
    public ResponseEntity<List<ParserEntity>> getParsersByMessageType(@PathVariable String messageType) {

        List<ParserEntity> result = configManagementService.getParsersByMessageType(messageType);
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/{id}/priority")
    public ResponseEntity<ParserEntity> updateParserPriority(
            @PathVariable Long id,
            @RequestParam Integer priority) {

        ParserEntity entity = configManagementService.updateParserPriority(id, priority);
        return ResponseEntity.ok(entity);
    }

    @PostMapping("/validate")
    public ResponseEntity<ConfigValidationService.ValidationResult> validateParser(
            @RequestBody ParserEntity entity) {

        ConfigValidationService.ValidationResult result = validationService.validateParser(entity);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/test")
    public ResponseEntity<?> testParser(@RequestBody ParserTestRequest request) {
        try {
            Map<String, Object> result = parseService.testParser(
                request.getType(), 
                request.getParam(), 
                request.getSampleData()
            );
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @lombok.Data
    public static class ParserTestRequest {
        private String type;
        private Object param;
        private String sampleData;
    }
}
