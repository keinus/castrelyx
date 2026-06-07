package org.keinus.logparser.interfaces.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.keinus.logparser.domain.configuration.service.ConfigValidationService;
import org.keinus.logparser.infrastructure.persistence.entity.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/validate")
@RequiredArgsConstructor
@Slf4j
public class ValidationController {

    private final ConfigValidationService validationService;

    @GetMapping("/pipeline")
    public ResponseEntity<ConfigValidationService.PipelineIntegrityResult> validatePipeline() {

        ConfigValidationService.PipelineIntegrityResult result = validationService.validatePipelineIntegrity();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/input")
    public ResponseEntity<ConfigValidationService.ValidationResult> validateInput(
            @RequestBody InputAdapterEntity entity) {

        ConfigValidationService.ValidationResult result = validationService.validateInputAdapter(entity);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/parser")
    public ResponseEntity<ConfigValidationService.ValidationResult> validateParser(
            @RequestBody ParserEntity entity) {

        ConfigValidationService.ValidationResult result = validationService.validateParser(entity);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/transform")
    public ResponseEntity<ConfigValidationService.ValidationResult> validateTransform(
            @RequestBody TransformEntity entity) {

        ConfigValidationService.ValidationResult result = validationService.validateTransform(entity);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/output")
    public ResponseEntity<ConfigValidationService.ValidationResult> validateOutput(
            @RequestBody OutputAdapterEntity entity) {

        ConfigValidationService.ValidationResult result = validationService.validateOutputAdapter(entity);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/errors")
    public ResponseEntity<List<ConfigValidationService.ValidationError>> getAllErrors() {

        List<ConfigValidationService.ValidationError> errors = validationService.getAllValidationErrors();
        return ResponseEntity.ok(errors);
    }
}
