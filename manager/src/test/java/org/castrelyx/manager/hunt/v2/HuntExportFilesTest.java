package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HuntExportFilesTest {
  @TempDir Path folder;
  private Clock clock;
  private AtomicLong monotonic;
  private HuntExportFiles files;
  private Path lastFile;
  private FileChannel lastChannel;

  @BeforeEach
  void setup() {
    clock = mock(Clock.class);
    when(clock.instant()).thenReturn(Instant.parse("2026-10-21T00:00:00Z"));
    monotonic = new AtomicLong();
    files =
        new HuntExportFiles(
            1024,
            clock,
            monotonic::get,
            () -> {
              lastFile = Files.createTempFile(folder, "case-", ".json");
              lastChannel =
                  FileChannel.open(
                      lastFile,
                      StandardOpenOption.READ,
                      StandardOpenOption.WRITE,
                      StandardOpenOption.DELETE_ON_CLOSE);
              return lastChannel;
            });
  }

  @AfterEach
  void cleanup() {
    files.close();
  }

  @Test
  void fullFileDigestAndLengthMatchConsumedBytesAndHandleDeletion() throws Exception {
    var staged = files.begin();
    byte[] bytes = "{\"notes\":\"한글·雪·🔎\"}".getBytes(StandardCharsets.UTF_8);
    staged.output().write(bytes);
    var artifact = staged.finish("case.json");
    assertThat(artifact.length()).isEqualTo(bytes.length);
    assertThat(artifact.sha256())
        .isEqualTo(HuntWorkspace.hash(new String(bytes, StandardCharsets.UTF_8)));
    try (var input = artifact.resource().getInputStream()) {
      assertThat(input.readAllBytes()).isEqualTo(bytes);
    }
    assertThat(lastChannel.isOpen()).isFalse();
    assertThat(Files.exists(lastFile)).isFalse();
    assertThatThrownBy(() -> artifact.resource().getInputStream())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sizeBoundaryAcceptsExactLimitAndRejectsNextByteBeforeArtifactExists() throws Exception {
    try (var staged = files.begin()) {
      staged.output().write(new byte[1024]);
      assertThatThrownBy(() -> staged.output().write(1))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("파일 크기 한도");
    }
    assertThat(Files.exists(lastFile)).isFalse();
    try (var staged = files.begin()) {
      staged.output().write(new byte[1024]);
      assertThat(staged.finish("exact.json").length()).isEqualTo(1024);
    }
  }

  @Test
  void completedFileCannotChangeAfterItsHashWasCalculated() throws Exception {
    try (var staged = files.begin()) {
      var output = staged.output();
      output.write(1);
      staged.finish("immutable.json");
      assertThatThrownBy(() -> output.write(2))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("변경할 수 없습니다");
    }
  }

  @Test
  void preparationTimeoutClosesTheUnusableFileAndReleasesCapacity() throws Exception {
    try (var staged = files.begin()) {
      monotonic.set(TimeUnit.SECONDS.toNanos(61));
      assertThatThrownBy(() -> staged.output().write(1))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("60초");
    }
    assertThat(lastChannel.isOpen()).isFalse();
    assertThat(Files.exists(lastFile)).isFalse();
    try (var first = files.begin();
        var second = files.begin()) {
      assertThatThrownBy(files::begin).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void capacityIsHeldUntilDownloadsCloseAndAnUnusedReadyFileExpires() throws Exception {
    var first = files.begin();
    first.output().write(1);
    var ready = first.finish("pending.json");
    var second = files.begin();
    second.output().write(2);
    var active = second.finish("streaming.json");
    var input = active.resource().getInputStream();
    assertThatThrownBy(files::begin).isInstanceOf(IllegalStateException.class);
    when(clock.instant()).thenReturn(Instant.parse("2026-10-21T00:15:00Z"));
    files.expireUnused();
    assertThatThrownBy(() -> ready.resource().getInputStream()).isInstanceOf(IOException.class);
    assertThat(input.read()).isEqualTo(2);
    try (var replacement = files.begin()) {
      assertThatThrownBy(files::begin).isInstanceOf(IllegalStateException.class);
    }
    input.close();
    try (var available1 = files.begin();
        var available2 = files.begin()) {}
  }

  @Test
  void storageCreationFailureDoesNotConsumeAConcurrencySlot() throws Exception {
    var factory = mock(HuntExportFiles.FileFactory.class);
    when(factory.open()).thenThrow(new IOException("disk fixture"));
    var failing = new HuntExportFiles(1024, clock, monotonic::get, factory);
    for (int i = 0; i < 3; i++)
      assertThatThrownBy(failing::begin)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("저장 장치");
    verify(factory, times(3)).open();
    failing.close();
  }
}
