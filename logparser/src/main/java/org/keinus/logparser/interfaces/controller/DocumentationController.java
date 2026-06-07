package org.keinus.logparser.interfaces.controller;

import lombok.RequiredArgsConstructor;
import org.keinus.logparser.application.service.DocumentationService;
import org.keinus.logparser.interfaces.dto.response.DocumentationContentDTO;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/docs")
@RequiredArgsConstructor
public class DocumentationController {

    private final DocumentationService documentationService;

    @GetMapping("/content")
    public ResponseEntity<DocumentationContentDTO> getDocumentationContent(
            @RequestParam(defaultValue = "README.md") String path
    ) throws IOException {
        DocumentationService.DocumentAsset asset = documentationService.readTextDocument(path);

        return ResponseEntity.ok(new DocumentationContentDTO(
                asset.path(),
                asset.mediaType().toString(),
                asset.asText()
        ));
    }

    @GetMapping("/raw")
    public ResponseEntity<ByteArrayResource> getDocumentationAsset(
            @RequestParam String path
    ) throws IOException {
        DocumentationService.DocumentAsset asset = documentationService.readRawDocument(path);
        String filename = Path.of(asset.path()).getFileName().toString();

        return ResponseEntity.ok()
                .contentType(asset.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(new ByteArrayResource(asset.bytes()));
    }
}
