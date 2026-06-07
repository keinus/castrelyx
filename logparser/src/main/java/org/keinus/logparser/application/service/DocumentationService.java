package org.keinus.logparser.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

@Service
@Slf4j
public class DocumentationService {

    private static final MediaType MARKDOWN_MEDIA_TYPE = MediaType.parseMediaType("text/markdown");

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "markdown", "mdj", "json", "mmd", "txt", "html"
    );

    private static final Set<String> RAW_EXTENSIONS = Set.of(
            "md", "markdown", "mdj", "json", "mmd", "txt", "html",
            "png", "jpg", "jpeg", "gif", "svg", "webp"
    );

    private static final Set<String> ALLOWED_ROOT_FILES = Set.of(
            "README.md", "AGENTS.md"
    );

    private static final Set<String> ALLOWED_ROOT_DIRECTORIES = Set.of(
            "readme", "docs"
    );

    private final Path workspaceRoot;

    public DocumentationService() {
        this(Paths.get("").toAbsolutePath().normalize());
    }

    DocumentationService(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.normalize();
    }

    public DocumentAsset readTextDocument(String requestedPath) throws IOException {
        Path resolvedPath = resolveAllowedPath(requestedPath, true);
        return readDocument(resolvedPath);
    }

    public DocumentAsset readRawDocument(String requestedPath) throws IOException {
        Path resolvedPath = resolveAllowedPath(requestedPath, false);
        return readDocument(resolvedPath);
    }

    private DocumentAsset readDocument(Path resolvedPath) throws IOException {
        byte[] bytes = Files.readAllBytes(resolvedPath);
        String relativePath = workspaceRoot.relativize(resolvedPath).toString().replace('\\', '/');
        MediaType mediaType = resolveMediaType(resolvedPath);

        log.debug("Serving documentation asset: {}", relativePath);
        return new DocumentAsset(relativePath, mediaType, bytes);
    }

    private Path resolveAllowedPath(String requestedPath, boolean textOnly) {
        if (!StringUtils.hasText(requestedPath)) {
            throw badRequest("Document path is required");
        }

        String normalizedInput = requestedPath.trim();
        if (normalizedInput.indexOf('\0') >= 0) {
            throw badRequest("Document path contains invalid characters");
        }

        final Path relativePath;
        try {
            relativePath = Paths.get(normalizedInput).normalize();
        } catch (InvalidPathException e) {
            throw badRequest("Document path is invalid");
        }

        if (relativePath.isAbsolute() || relativePath.getNameCount() == 0 || relativePath.startsWith("..")) {
            throw badRequest("Document path must stay within the documentation roots");
        }

        if (!isAllowedRelativePath(relativePath)) {
            throw badRequest("Document path is outside the allowed documentation roots");
        }

        String extension = getExtension(relativePath.getFileName().toString());
        if (textOnly && !TEXT_EXTENSIONS.contains(extension)) {
            throw badRequest("Requested document is not a text resource");
        }

        if (!RAW_EXTENSIONS.contains(extension)) {
            throw badRequest("Requested document type is not supported");
        }

        Path resolvedPath = workspaceRoot.resolve(relativePath).normalize();
        if (!resolvedPath.startsWith(workspaceRoot)) {
            throw badRequest("Document path must stay within the workspace");
        }

        if (!Files.exists(resolvedPath) || Files.isDirectory(resolvedPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }

        return resolvedPath;
    }

    private boolean isAllowedRelativePath(Path relativePath) {
        String normalizedPath = relativePath.toString().replace('\\', '/');
        if (relativePath.getNameCount() == 1) {
            return ALLOWED_ROOT_FILES.contains(normalizedPath);
        }

        String firstSegment = relativePath.getName(0).toString();
        return ALLOWED_ROOT_DIRECTORIES.contains(firstSegment);
    }

    private MediaType resolveMediaType(Path path) {
        String filename = path.getFileName().toString();
        String extension = getExtension(filename);

        return switch (extension) {
            case "md", "markdown" -> MARKDOWN_MEDIA_TYPE;
            case "mdj", "json" -> MediaType.APPLICATION_JSON;
            case "mmd", "txt" -> MediaType.TEXT_PLAIN;
            case "html" -> MediaType.TEXT_HTML;
            default -> MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM);
        };
    }

    private String getExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record DocumentAsset(String path, MediaType mediaType, byte[] bytes) {
        public String asText() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
