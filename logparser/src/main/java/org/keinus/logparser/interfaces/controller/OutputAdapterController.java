package org.keinus.logparser.interfaces.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.keinus.logparser.domain.configuration.service.ConfigManagementService;
import org.keinus.logparser.infrastructure.persistence.entity.OutputAdapterEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/output-adapters")
@RequiredArgsConstructor
@Slf4j
public class OutputAdapterController {

    private final ConfigManagementService configManagementService;

    @GetMapping
    public ResponseEntity<Page<OutputAdapterEntity>> getAllOutputAdapters(Pageable pageable) {

        Page<OutputAdapterEntity> result = configManagementService.getAllOutputAdapters(pageable);
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<OutputAdapterEntity> createOutputAdapter(@RequestBody OutputAdapterEntity entity) {

        OutputAdapterEntity created = configManagementService.createOutputAdapter(entity);
        return ResponseEntity.ok(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OutputAdapterEntity> getOutputAdapter(@PathVariable("id") Long id) {

        OutputAdapterEntity entity = configManagementService.getOutputAdapter(id);
        return ResponseEntity.ok(entity);
    }

    @PutMapping("/{id}")
    public ResponseEntity<OutputAdapterEntity> updateOutputAdapter(
            @PathVariable("id") Long id,
            @RequestBody OutputAdapterEntity entity) {

        OutputAdapterEntity updated = configManagementService.updateOutputAdapter(id, entity);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOutputAdapter(@PathVariable("id") Long id) {

        configManagementService.deleteOutputAdapter(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<List<OutputAdapterEntity>> getOutputAdaptersByType(@PathVariable("type") String type) {

        List<OutputAdapterEntity> result = configManagementService.getOutputAdaptersByType(type);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/messagetype/{messageType}")
    public ResponseEntity<List<OutputAdapterEntity>> getOutputAdaptersByMessageType(@PathVariable("messageType") String messageType) {

        List<OutputAdapterEntity> result = configManagementService.getOutputAdaptersByMessageType(messageType);
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/{id}/enable")
    public ResponseEntity<OutputAdapterEntity> enableOutputAdapter(@PathVariable("id") Long id) {

        OutputAdapterEntity entity = configManagementService.enableOutputAdapter(id);
        return ResponseEntity.ok(entity);
    }

    @PatchMapping("/{id}/disable")
    public ResponseEntity<OutputAdapterEntity> disableOutputAdapter(@PathVariable("id") Long id) {

        OutputAdapterEntity entity = configManagementService.disableOutputAdapter(id);
        return ResponseEntity.ok(entity);
    }
}
