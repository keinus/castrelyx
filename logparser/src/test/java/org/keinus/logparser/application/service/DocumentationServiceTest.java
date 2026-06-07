package org.keinus.logparser.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class DocumentationServiceTest {

    private final DocumentationService documentationService =
            new DocumentationService(Paths.get("").toAbsolutePath().normalize());

    @Test
    @DisplayName("README 문서를 텍스트로 읽을 수 있다")
    void shouldReadReadmeAsText() throws IOException {
        DocumentationService.DocumentAsset asset = documentationService.readTextDocument("README.md");

        assertEquals("README.md", asset.path());
        assertEquals("text/markdown", asset.mediaType().toString());
        assertTrue(asset.asText().contains("# Logparser"));
    }

    @Test
    @DisplayName("문서 디렉터리의 이미지 자산을 raw로 읽을 수 있다")
    void shouldReadReadmeAssetAsRaw() throws IOException {
        DocumentationService.DocumentAsset asset = documentationService.readRawDocument("readme/mermaid-diagram-2025-07-30-141053.png");

        assertEquals("readme/mermaid-diagram-2025-07-30-141053.png", asset.path());
        assertEquals("image/png", asset.mediaType().toString());
        assertTrue(asset.bytes().length > 0);
    }

    @Test
    @DisplayName("허용되지 않은 경로는 차단된다")
    void shouldRejectDisallowedPath() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> documentationService.readTextDocument("build.gradle"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    @DisplayName("상위 경로 이동은 차단된다")
    void shouldRejectPathTraversal() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> documentationService.readTextDocument("../README.md"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    @DisplayName("허용된 영역 안의 없는 파일은 404를 반환한다")
    void shouldReturnNotFoundForMissingDocument() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> documentationService.readTextDocument("readme/missing.md"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }
}
