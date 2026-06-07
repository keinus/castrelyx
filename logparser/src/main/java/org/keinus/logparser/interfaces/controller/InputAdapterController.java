package org.keinus.logparser.interfaces.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.keinus.logparser.domain.configuration.service.ConfigManagementService;
import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/input-adapters")
@RequiredArgsConstructor
@Slf4j
public class InputAdapterController {

    private final ConfigManagementService configManagementService;

    @GetMapping
    public ResponseEntity<Page<InputAdapterEntity>> getAllInputAdapters(Pageable pageable) {

        Page<InputAdapterEntity> result = configManagementService.getAllInputAdapters(pageable);
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<InputAdapterEntity> createInputAdapter(@RequestBody InputAdapterEntity entity) {

        InputAdapterEntity created = configManagementService.createInputAdapter(entity);
        return ResponseEntity.ok(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<InputAdapterEntity> getInputAdapter(@PathVariable("id") Long id) {

        InputAdapterEntity entity = configManagementService.getInputAdapter(id);
        return ResponseEntity.ok(entity);
    }

    @PutMapping("/{id}")
    public ResponseEntity<InputAdapterEntity> updateInputAdapter(
            @PathVariable("id") Long id,
            @RequestBody InputAdapterEntity entity) {

        InputAdapterEntity updated = configManagementService.updateInputAdapter(id, entity);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteInputAdapter(@PathVariable("id") Long id) {
        configManagementService.deleteInputAdapter(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<List<InputAdapterEntity>> getInputAdaptersByType(@PathVariable("type") String type) {

        List<InputAdapterEntity> result = configManagementService.getInputAdaptersByType(type);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/messagetype/{messageType}")
    public ResponseEntity<InputAdapterEntity> getInputAdapterByMessageType(@PathVariable("messageType") String messageType) {

        InputAdapterEntity entity = configManagementService.getInputAdapterByMessageType(messageType);
        return ResponseEntity.ok(entity);
    }

    @PatchMapping("/{id}/enable")
    public ResponseEntity<InputAdapterEntity> enableInputAdapter(@PathVariable("id") Long id) {

        InputAdapterEntity entity = configManagementService.enableInputAdapter(id);
        return ResponseEntity.ok(entity);
    }

    @PatchMapping("/{id}/disable")
    public ResponseEntity<InputAdapterEntity> disableInputAdapter(@PathVariable("id") Long id) {

        InputAdapterEntity entity = configManagementService.disableInputAdapter(id);
        return ResponseEntity.ok(entity);
    }
}
