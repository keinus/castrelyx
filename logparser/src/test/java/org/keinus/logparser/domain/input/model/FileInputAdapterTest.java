package org.keinus.logparser.domain.input.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileInputAdapterTest {

    private InputAdapterConfig config;
    private FileInputAdapter adapter;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        config = new InputAdapterConfig();
        config.setType("FileInputAdapter");
        config.setMessagetype("file-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (adapter != null) adapter.close();
    }

    @Test
    @DisplayName("Constructor should throw exception if path is missing")
    void constructorMissingPath() {
        config.setPath(null);
        assertThrows(IllegalArgumentException.class, () -> new FileInputAdapter(config));
    }

    @Test
    @DisplayName("Should read lines from file")
    void readLines() throws IOException {
        Path logFile = tempDir.resolve("test.log");
        Files.writeString(logFile, "Line 1\nLine 2\n");

        config.setPath(logFile.toString());
        config.setIsFromBeginning(true);
        
        adapter = new FileInputAdapter(config);
        
        LogEvent event1 = adapter.run();
        assertThat(event1).isNotNull();
        assertThat(event1.getOriginalText()).isEqualTo("Line 1");
        
        LogEvent event2 = adapter.run();
        assertThat(event2).isNotNull();
        assertThat(event2.getOriginalText()).isEqualTo("Line 2");
        
        assertThat(adapter.run()).isNull();
    }

    @Test
    @DisplayName("Should detect file growth")
    void fileGrowth() throws IOException {
        Path logFile = tempDir.resolve("growth.log");
        Files.writeString(logFile, "Initial\n");

        config.setPath(logFile.toString());
        config.setIsFromBeginning(true);
        
        adapter = new FileInputAdapter(config);
        assertThat(adapter.run().getOriginalText()).isEqualTo("Initial");
        assertThat(adapter.run()).isNull();

        // Append new line
        Files.writeString(logFile, "Appended\n", StandardOpenOption.APPEND);
        
        LogEvent event = adapter.run();
        assertThat(event).isNotNull();
        assertThat(event.getOriginalText()).isEqualTo("Appended");
    }

    @Test
    @DisplayName("Should detect log rotation")
    void logRotation() throws IOException {
        Path logFile = tempDir.resolve("rotate.log");
        Files.writeString(logFile, "Old line 1\nOld line 2\n");

        config.setPath(logFile.toString());
        config.setIsFromBeginning(true);
        
        adapter = new FileInputAdapter(config);
        adapter.run(); // Read Old line 1
        adapter.run(); // Read Old line 2
        
        // Rotate: overwrite with smaller file
        Files.writeString(logFile, "New line 1\n", StandardOpenOption.TRUNCATE_EXISTING);
        
        LogEvent event = adapter.run();
        assertThat(event).isNotNull();
        assertThat(event.getOriginalText()).isEqualTo("New line 1");
    }

    @Test
    void tailFromEndReadsAllNewLinesAfterRotation() throws IOException {
        Path logFile = tempDir.resolve("tail-rotate.log");
        Files.writeString(logFile, "Existing content before the tail reader starts\n");
        config.setPath(logFile.toString());
        config.setIsFromBeginning(false);
        adapter = new FileInputAdapter(config);
        assertThat(adapter.run()).isNull();

        Files.writeString(logFile, "New 1\nNew 2\n", StandardOpenOption.TRUNCATE_EXISTING);

        assertThat(adapter.run().getOriginalText()).isEqualTo("New 1");
        assertThat(adapter.run().getOriginalText()).isEqualTo("New 2");
        assertThat(adapter.run()).isNull();
        Files.writeString(logFile, "New 3\n", StandardOpenOption.APPEND);
        assertThat(adapter.run().getOriginalText()).isEqualTo("New 3");
    }
}
