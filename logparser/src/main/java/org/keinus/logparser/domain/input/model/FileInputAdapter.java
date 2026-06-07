package org.keinus.logparser.domain.input.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.infrastructure.util.ThreadUtil;
import org.keinus.logparser.domain.model.LogEvent;

/**
 * 지정된 파일로부터 로그 메시지를 라인 단위로 읽어오는 입력 어댑터입니다.
 * <p>
 * {@link InputAdapter}를 구현하며, BufferedReader를 사용하여
 * 파일의 새로운 내용을 라인 단위로 안전하게 읽어들입니다. 'tail -f'와 유사한 동작을 수행합니다.
 * <p>
 * 주요 기능:
 * <ul>
 *     <li><b>파일 추적:</b> 파일의 라인 수를 기억하고, 새로운 라인이 추가될 때만 읽습니다.</li>
 *     <li><b>로그 로테이션 감지:</b> 파일의 라인 수가 줄어드는 것을 감지하여 로그 파일이 로테이션되었음을 인지하고,
 *         파일을 다시 열어 처음부터 읽기 시작합니다.</li>
 *     <li><b>라인 단위 읽기:</b> BufferedReader.readLine()을 사용하여 개행 문자를 안전하게 처리합니다.</li>
 *     <li><b>시작 위치 설정:</b> {@code isFromBeginning} 설정을 통해 파일의 처음부터 읽을지,
 *         아니면 현재 끝에서부터 읽을지를 결정할 수 있습니다.</li>
 * </ul>
 *
 * @see org.keinus.logparser.core.interfaces.InputAdapter
 * @see java.io.BufferedReader
 */
public class FileInputAdapter extends InputAdapter {
    private static final Logger logger = LoggerFactory.getLogger(FileInputAdapter.class);
    private final Path filePath;
    private long currentLineNumber;
    private final boolean isFromBeginning;
    private BufferedReader reader;
    private final String hostName;
    private int fileOpenRetryCount = 0;
    private static final int MAX_FILE_OPEN_RETRY = 12; // 1분 동안 재시도
    private long lastFileSize = 0;

    public FileInputAdapter(InputAdapterConfig config) throws IOException {
        super(config);
        String pathStr = config.getPath();
        if (pathStr == null || pathStr.isEmpty()) {
            throw new IllegalArgumentException("File path must not be null or empty.");
        }
        this.filePath = Paths.get(pathStr);
        if (Files.exists(filePath) && Files.isDirectory(filePath)) {
            throw new IllegalArgumentException("File path must not be a directory: " + filePath);
        }
        this.isFromBeginning = config.getIsFromBeginning() != null ? config.getIsFromBeginning() : false;
        this.hostName = java.net.InetAddress.getLocalHost().getHostName();
        this.currentLineNumber = 0;
        logger.info("File Input Adapter initialized for path: {}. Reading from beginning: {}. Host: {}",
            filePath, isFromBeginning, hostName);
    }

    /**
     * Safely closes the current reader if it exists.
     */
    private void safeCloseReader() {
        if (reader != null) {
            try {
                reader.close();
                logger.debug("Reader closed successfully");
            } catch (IOException e) {
                logger.warn("Error closing reader: {}", e.getMessage());
            } finally {
                reader = null;
            }
        }
    }

    /**
     * Opens the file and sets the initial line position.
     * This method is called lazily when needed.
     */
    private void openFile() {
        // 기존 reader가 있으면 먼저 닫기
        safeCloseReader();

        while (fileOpenRetryCount < MAX_FILE_OPEN_RETRY) {
            try {
                if (!Files.exists(filePath)) {
                    logger.error("File not found: {}. Waiting for file to be created...", filePath);
                    ThreadUtil.sleep(5000);
                    fileOpenRetryCount++;
                    continue;
                }
                if (Files.isDirectory(filePath)) {
                    throw new IOException("File path is a directory: " + filePath);
                }

                this.reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8);
                this.lastFileSize = Files.size(filePath);

                if (isFromBeginning) {
                    this.currentLineNumber = 0;
                } else {
                    // 파일 끝까지 건너뛰기
                    this.currentLineNumber = 0;
                    while (reader.readLine() != null) {
                        this.currentLineNumber++;
                    }
                }

                logger.info("File opened for {}. Starting from line: {}", filePath, currentLineNumber);
                fileOpenRetryCount = 0;
                return;
            } catch (IOException e) {
                logger.error("Failed to open file for {}: {}", filePath, e.getMessage());
                safeCloseReader(); // 실패 시에도 reader 정리
                ThreadUtil.sleep(5000);
                fileOpenRetryCount++;
            }
        }
        throw new IllegalStateException("File could not be opened after multiple retries: " + filePath);
    }

    /**
     * Safely reopens the file from a specific line number.
     * This is used when detecting file growth or rotation.
     */
    private void reopenFile(long fromLineNumber) throws IOException {
        safeCloseReader();

        try {
            this.reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8);
            this.lastFileSize = Files.size(filePath);

            // Skip to the desired line number
            for (long i = 0; i < fromLineNumber; i++) {
                if (reader.readLine() == null) {
                    logger.warn("Expected {} lines but found fewer. File may have rotated.", fromLineNumber);
                    currentLineNumber = i;
                    return;
                }
            }
            currentLineNumber = fromLineNumber;
            logger.debug("File reopened at line {}", currentLineNumber);
        } catch (IOException e) {
            safeCloseReader();
            throw e;
        }
    }

    @Override
    public LogEvent run() {
        // 파일이 열리지 않았으면 열기
        if (reader == null) {
            try {
                openFile();
            } catch (Exception e) {
                logger.error("File open failed: {}", e.getMessage());
                return null;
            }
        }

        if (reader == null) {
            return null;
        }

        try {
            long currentFileSize = Files.size(filePath);

            // 로그 로테이션 감지: 파일 크기가 이전보다 작아진 경우
            if (currentFileSize < lastFileSize) {
                logger.info("Log rotation detected (file size decreased: {} -> {}). Re-opening file.",
                    lastFileSize, currentFileSize);
                openFile();
                if (reader == null) {
                    return null;
                }
                currentFileSize = Files.size(filePath);
            }

            lastFileSize = currentFileSize;

            // 라인 읽기
            String line = reader.readLine();
            if (line != null) {
                currentLineNumber++;
                logger.debug("Read line {}: {}", currentLineNumber,
                    line.length() > 100 ? line.substring(0, 100) + "..." : line);
                return createLogEvent(line);
            }

            // 파일 끝에 도달했지만 파일 크기가 증가했을 수 있음
            // reader를 닫고 다시 열어서 새로운 내용을 읽음
            long newFileSize = Files.size(filePath);
            if (newFileSize > currentFileSize) {
                logger.debug("File size increased ({} -> {}). Re-opening to read new content.",
                    currentFileSize, newFileSize);
                long savedLineNumber = currentLineNumber;
                reopenFile(savedLineNumber);
            }

        } catch (IOException e) {
            logger.error("An error occurred while reading the file: {}", e.getMessage());
            safeCloseReader();
            ThreadUtil.sleep(5000);
        }

        return null;
    }

    @Override
    public void close() throws IOException {
        safeCloseReader();
        logger.info("File Input Adapter closed for: {}", filePath);
    }
}
