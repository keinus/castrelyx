package org.castrelyx.manager.hunt.v2;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded, single-use download files whose open handles own deletion and concurrency leases. */
@Component
public class HuntExportFiles {
  interface FileFactory {
    FileChannel open() throws IOException;
  }

  private final long maxBytes, timeoutNanos;
  private final Clock clock;
  private final LongSupplier monotonic;
  private final FileFactory factory;
  private final Semaphore slots = new Semaphore(2);
  private final Set<Staged> open = ConcurrentHashMap.newKeySet();

  @Autowired
  public HuntExportFiles(
      @Value("${manager.hunt.export-max-bytes:268435456}") long maxBytes, Clock clock) {
    this(
        maxBytes,
        clock,
        System::nanoTime,
        () -> {
          Path file = Files.createTempFile("castrelyx-hunt-export-", ".json");
          try {
            return FileChannel.open(
                file,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.DELETE_ON_CLOSE);
          } catch (IOException error) {
            Files.deleteIfExists(file);
            throw error;
          }
        });
  }

  HuntExportFiles(long maxBytes, Clock clock, LongSupplier monotonic, FileFactory factory) {
    if (maxBytes < 1024 || maxBytes > 1073741824L)
      throw new IllegalArgumentException("내보내기 파일 한도는 1KiB~1GiB 범위여야 합니다.");
    this.maxBytes = maxBytes;
    this.clock = clock;
    this.monotonic = monotonic;
    this.factory = factory;
    timeoutNanos = TimeUnit.SECONDS.toNanos(60);
  }

  public Staged begin() {
    if (!slots.tryAcquire()) throw new IllegalStateException("다른 내보내기를 준비·전송 중입니다. 완료 후 다시 시도하세요.");
    try {
      var value = new Staged(factory.open());
      open.add(value);
      return value;
    } catch (IOException | RuntimeException error) {
      slots.release();
      throw new IllegalStateException("내보내기 임시 파일을 만들 수 없습니다. 저장 장치를 확인하세요.", error);
    }
  }

  public record Download(
      InputStreamResource resource, long length, String sha256, String filename, Staged lease)
      implements AutoCloseable {
    @Override
    public void close() {
      lease.close();
    }
  }

  public final class Staged implements AutoCloseable {
    private final FileChannel channel;
    private final Instant createdAt = clock.instant();
    private final long started = monotonic.getAsLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean ready, streaming;
    private long size;

    private Staged(FileChannel channel) {
      this.channel = channel;
    }

    private void budget(int count) throws IOException {
      if (closed.get()) throw new IOException("내보내기 파일이 닫혔습니다.");
      if (ready) throw new IOException("완성한 내보내기 파일은 변경할 수 없습니다.");
      if (monotonic.getAsLong() - started > timeoutNanos)
        throw new IOException("내보내기 준비 시간 60초를 초과했습니다.");
      if (count > maxBytes - size)
        throw new IOException("내보내기 파일 크기 한도를 초과했습니다. 내보내기 설정과 저장 공간을 확인하세요.");
    }

    public OutputStream output() {
      return new OutputStream() {
        @Override
        public void write(int value) throws IOException {
          write(new byte[] {(byte) value});
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
          budget(length);
          var buffer = ByteBuffer.wrap(bytes, offset, length);
          while (buffer.hasRemaining()) channel.write(buffer);
          size += length;
        }

        // Jackson closes its output; the channel must remain open until the response is consumed.
        @Override
        public void close() {}
      };
    }

    public synchronized Download finish(String filename) throws IOException {
      budget(0);
      if (ready) throw new IOException("이미 준비한 내보내기입니다.");
      channel.position(0);
      MessageDigest hash;
      try {
        hash = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException error) {
        throw new IllegalStateException(error);
      }
      var buffer = ByteBuffer.allocate(32768);
      while (channel.read(buffer) != -1) {
        budget(0);
        buffer.flip();
        hash.update(buffer);
        buffer.clear();
      }
      channel.position(0);
      ready = true;
      var resource =
          new InputStreamResource(
              () -> {
                synchronized (this) {
                  if (closed.get() || streaming)
                    throw new IOException("내보내기 파일이 만료되었거나 이미 전송 중입니다.");
                  streaming = true;
                }
                return new FilterInputStream(Channels.newInputStream(channel)) {
                  @Override
                  public void close() throws IOException {
                    try {
                      super.close();
                    } finally {
                      Staged.this.close();
                    }
                  }
                };
              },
              "prepared investigation export");
      return new Download(resource, size, HexFormat.of().formatHex(hash.digest()), filename, this);
    }

    private synchronized void expireUnused(Instant now) {
      if (ready && !streaming && !createdAt.plusSeconds(900).isAfter(now)) close();
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) return;
      try {
        channel.close();
      } catch (IOException error) {
        org.slf4j.LoggerFactory.getLogger(HuntExportFiles.class)
            .warn("Cannot close an investigation export file", error);
      } finally {
        open.remove(this);
        slots.release();
      }
    }
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void expireUnused() {
    Instant now = clock.instant();
    open.forEach(value -> value.expireUnused(now));
  }

  @PreDestroy
  public void close() {
    open.forEach(Staged::close);
  }
}
