package org.keinus.logparser.interfaces.controller;

import java.util.Map;
import org.keinus.logparser.domain.transformation.service.TelemetryAssetBindings;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/structure/asset-bindings")
public class TelemetryAssetBindingsController {
    private final TelemetryAssetBindings bindings;
    public TelemetryAssetBindingsController(TelemetryAssetBindings bindings) { this.bindings = bindings; }

    @GetMapping
    public Map<String, String> list() { return bindings.snapshot(); }

    @PutMapping
    public Map<String, String> replace(@RequestBody Map<String, String> replacement) {
        bindings.replace(replacement);
        return bindings.snapshot();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidBinding(IllegalArgumentException error) {
        return Map.of("error", error.getMessage());
    }
}
