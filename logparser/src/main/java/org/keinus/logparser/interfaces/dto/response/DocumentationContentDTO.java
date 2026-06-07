package org.keinus.logparser.interfaces.dto.response;

public record DocumentationContentDTO(
        String path,
        String mediaType,
        String content
) {
}
