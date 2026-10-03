package org.keinus.logparser.interfaces.controller;

import java.util.Map;
import org.keinus.logparser.domain.input.model.RouterInbox;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/router")
public class RouterIngestionController {
    @GetMapping("/status")
    public Map<String,Object> status(@RequestParam String assetUid) { return RouterInbox.status(assetUid); }
}
