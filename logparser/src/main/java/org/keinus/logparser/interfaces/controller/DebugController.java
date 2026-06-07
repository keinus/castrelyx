package org.keinus.logparser.interfaces.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;
import org.keinus.logparser.infrastructure.util.ThreadManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/debug")
@RequiredArgsConstructor
@Slf4j
public class DebugController {

    private final ApplicationProperties applicationProperties;
    private final ThreadManager threadManager;

    @GetMapping("/app-properties")
    public ResponseEntity<Map<String, Object>> getApplicationProperties() {
        log.info("DEBUG: Checking ApplicationProperties state");

        Map<String, Object> result = new HashMap<>();

        try {
            var input = applicationProperties.getInput();
            var output = applicationProperties.getOutput();
            var parser = applicationProperties.getParser();

            result.put("input_count", input == null ? "NULL" : input.size());
            result.put("output_count", output == null ? "NULL" : output.size());
            result.put("parser_count", parser == null ? "NULL" : parser.size());
            result.put("parser_threads", applicationProperties.getParserThreads());
            result.put("flush_interval", applicationProperties.getFlushInterval());

            if (input != null && !input.isEmpty()) {
                result.put("first_input_type", input.get(0).getType());
                result.put("first_input_messagetype", input.get(0).getMessagetype());
            }

            if (output != null && !output.isEmpty()) {
                result.put("first_output_type", output.get(0).getType());
                result.put("first_output_messagetype", output.get(0).getMessagetype());
            }

            log.info("DEBUG: Input count = {}, Output count = {}, Parser count = {}",
                    input == null ? "NULL" : input.size(),
                    output == null ? "NULL" : output.size(),
                    parser == null ? "NULL" : parser.size());

        } catch (Exception e) {
            log.error("DEBUG: Error reading ApplicationProperties", e);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/thread-manager")
    public ResponseEntity<Map<String, Object>> getThreadManagerState() {
        log.info("DEBUG: Checking ThreadManager state");

        Map<String, Object> result = new HashMap<>();

        try {
            List<ThreadManager.ThreadInfo> allThreads = threadManager.getAllThreadInfo();
            List<String> activeThreads = threadManager.getActiveThreads();

            result.put("total_threads", allThreads.size());
            result.put("active_threads_count", activeThreads.size());
            result.put("active_thread_names", activeThreads);

            List<Map<String, Object>> threadDetails = allThreads.stream().map(t -> {
                Map<String, Object> detail = new HashMap<>();
                detail.put("name", t.name());           // record 방식
                detail.put("id", t.id());               // record 방식
                detail.put("state", t.state().toString()); // record 방식
                detail.put("alive", t.alive());         // record 방식
                return detail;
            }).toList();

            result.put("threads", threadDetails);

            log.info("DEBUG: ThreadManager has {} threads registered", allThreads.size());
            for (String name : activeThreads) {
                log.info("DEBUG: Active thread: {}", name);
            }

        } catch (Exception e) {
            log.error("DEBUG: Error reading ThreadManager", e);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}