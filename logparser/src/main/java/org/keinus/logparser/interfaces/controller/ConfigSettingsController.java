package org.keinus.logparser.interfaces.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.keinus.logparser.domain.configuration.service.ConfigManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
@Slf4j
public class ConfigSettingsController {

    private final ConfigManagementService configManagementService;

    @GetMapping
    public ResponseEntity<?> getAllSettings() {
        try {

            Map<String, Object> settings = configManagementService.getAllCommonSettings();
            return ResponseEntity.ok(settings);
        } catch (Exception e) {
            log.error("Error retrieving all settings", e);
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    @PutMapping
    public ResponseEntity<Void> updateSettings(@RequestBody Map<String, Object> settings) {

        configManagementService.updateCommonSettings(settings);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{key}")
    public ResponseEntity<String> getSettingValue(@PathVariable("key") String key) {
        try {

            String value = configManagementService.getConfigValue(key);
            return ResponseEntity.ok(value);
        } catch (Exception e) {
            log.error("Error retrieving setting for key: " + key, e);
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> updateSettingValue(
            @PathVariable("key") String key,
            @RequestBody Map<String, Object> payload) {

        Object value = payload.get("value");
        String dataType = (String) payload.getOrDefault("dataType", "STRING");
        configManagementService.setConfigValue(key, value, dataType);
        return ResponseEntity.ok(payload);
    }
}
