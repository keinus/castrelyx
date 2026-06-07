package org.keinus.logparser.interfaces.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.keinus.logparser.domain.configuration.service.ConfigManagementService;
import org.keinus.logparser.infrastructure.persistence.entity.TransformEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transforms")
@RequiredArgsConstructor
@Slf4j
public class TransformController {

    private final ConfigManagementService configManagementService;

    @GetMapping
    public ResponseEntity<Page<TransformEntity>> getAllTransforms(Pageable pageable) {

        Page<TransformEntity> result = configManagementService.getAllTransforms(pageable);
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<TransformEntity> createTransform(@RequestBody TransformEntity entity) {

        TransformEntity created = configManagementService.createTransform(entity);
        return ResponseEntity.ok(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransformEntity> getTransform(@PathVariable Long id) {

        TransformEntity entity = configManagementService.getTransform(id);
        return ResponseEntity.ok(entity);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransformEntity> updateTransform(
            @PathVariable Long id,
            @RequestBody TransformEntity entity) {

        TransformEntity updated = configManagementService.updateTransform(id, entity);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransform(@PathVariable Long id) {

        configManagementService.deleteTransform(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<List<TransformEntity>> getTransformsByType(@PathVariable String type) {

        List<TransformEntity> result = configManagementService.getTransformsByType(type);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/messagetype/{messageType}")
    public ResponseEntity<List<TransformEntity>> getTransformsByMessageType(@PathVariable String messageType) {

        List<TransformEntity> result = configManagementService.getTransformsByMessageType(messageType);
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/{id}/priority")
    public ResponseEntity<TransformEntity> updateTransformPriority(
            @PathVariable Long id,
            @RequestParam Integer priority) {

        TransformEntity entity = configManagementService.updateTransformPriority(id, priority);
        return ResponseEntity.ok(entity);
    }
}
