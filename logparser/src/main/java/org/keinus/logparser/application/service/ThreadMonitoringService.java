package org.keinus.logparser.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.entity.ParserEntity;
import org.keinus.logparser.infrastructure.persistence.entity.TransformEntity;
import org.keinus.logparser.infrastructure.persistence.repository.InputAdapterRepository;
import org.keinus.logparser.infrastructure.persistence.repository.ParserRepository;
import org.keinus.logparser.infrastructure.persistence.repository.TransformRepository;
import org.keinus.logparser.infrastructure.util.ThreadManager;
import org.keinus.logparser.interfaces.dto.response.ThreadDetailDto;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 스레드 모니터링 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThreadMonitoringService {

    private final ThreadManager threadManager;
    private final InputAdapterRepository inputAdapterRepository;
    private final ParserRepository parserRepository;
    private final TransformRepository transformRepository;

    private static final Pattern INPUT_ADAPTER_PATTERN = Pattern.compile("InputAdapter-(\\d+)-(.+)");
    private static final Pattern PROCESSING_PATTERN = Pattern.compile("ProcessingThread-(\\d+)");

    /**
     * 모든 활성 스레드의 상세 정보를 반환합니다.
     */
    public List<ThreadDetailDto> getAllThreadDetails() {
        Map<Long, InputAdapterEntity> inputAdaptersById = loadInputAdapters();
        List<ParserEntity> parsers = parserRepository.findAll();
        List<TransformEntity> transforms = transformRepository.findAll();

        return threadManager.getAllThreadInfo().stream()
                .map(info -> mapThreadInfoToDetail(info, inputAdaptersById, parsers, transforms))
                .toList();
    }

    /**
     * ThreadInfo를 ThreadDetailDto로 매핑합니다.
     */
    private ThreadDetailDto mapThreadInfoToDetail(
            ThreadManager.ThreadInfo threadInfo,
            Map<Long, InputAdapterEntity> inputAdaptersById,
            List<ParserEntity> parsers,
            List<TransformEntity> transforms
    ) {
        String threadName = threadInfo.name();  // record 방식

        ThreadDetailDto.ThreadDetailDtoBuilder builder = ThreadDetailDto.builder()
                .name(threadName)
                .threadId(threadInfo.id())              // record 방식
                .state(threadInfo.state().toString())   // record 방식
                .alive(threadInfo.alive())              // record 방식
                .interrupted(threadInfo.interrupted()); // record 방식

        // 스레드 이름을 기반으로 컴포넌트 타입 결정
        Matcher inputMatcher = INPUT_ADAPTER_PATTERN.matcher(threadName);
        Matcher processingMatcher = PROCESSING_PATTERN.matcher(threadName);

        if (inputMatcher.matches()) {
            mapInputAdapterThread(builder, inputMatcher, inputAdaptersById);
        } else if (processingMatcher.matches()) {
            mapProcessingThread(builder, processingMatcher, parsers, transforms);
        } else {
            mapSpecialThread(builder, threadName);
        }

        return builder.build();
    }

    private void mapInputAdapterThread(
            ThreadDetailDto.ThreadDetailDtoBuilder builder,
            Matcher matcher,
            Map<Long, InputAdapterEntity> inputAdaptersById
    ) {
        builder.componentType("INPUT");

        String adapterIdStr = matcher.group(1);
        String messageType = matcher.group(2);
        Long adapterId = Long.parseLong(adapterIdStr);

        InputAdapterEntity matchedAdapter = inputAdaptersById.get(adapterId);
        if (matchedAdapter != null) {
            builder.componentId(matchedAdapter.getId())
                   .componentName(matchedAdapter.getMessagetype() + 
                                  " (" + matchedAdapter.getType() + ")")
                   .componentConfig(buildInputAdapterConfig(matchedAdapter));
        } else {
            builder.componentName("InputAdapter #" + adapterId + " [" + messageType + "]")
                   .componentConfig(Map.of("id", adapterId, "messageType", messageType));
        }
    }

    private void mapProcessingThread(
            ThreadDetailDto.ThreadDetailDtoBuilder builder,
            Matcher matcher,
            List<ParserEntity> parsers,
            List<TransformEntity> transforms
    ) {
        String threadNumber = matcher.group(1);
        builder.componentType("PARSER") // Keeping generic PARSER/TRANSFORM or new PROCESSING? UI expects existing types likely.
               .componentName("Processing Worker #" + threadNumber)
               .metadata(Map.of(
                   "threadNumber", threadNumber, 
                   "totalParsers", parsers.size(),
                   "totalTransforms", transforms.size()
               ));
    }

    private void mapSpecialThread(
            ThreadDetailDto.ThreadDetailDtoBuilder builder,
            String threadName
    ) {
        switch (threadName) {
            case "QueueMonitor" -> builder
                    .componentType("MONITOR")
                    .componentName("Queue Monitor");
            default -> builder.componentType("UNKNOWN");
        }
    }

    private Map<String, Object> buildInputAdapterConfig(InputAdapterEntity adapter) {
        Map<String, Object> config = new HashMap<>();
        config.put("type", adapter.getType());
        config.put("enabled", adapter.getEnabled());

        Optional.ofNullable(adapter.getPort()).ifPresent(v -> config.put("port", v));
        Optional.ofNullable(adapter.getHost()).ifPresent(v -> config.put("host", v));
        Optional.ofNullable(adapter.getTopicid()).ifPresent(v -> config.put("topic", v));
        Optional.ofNullable(adapter.getPath()).ifPresent(v -> config.put("path", v));
        Optional.ofNullable(adapter.getBootstrapservers()).ifPresent(v -> config.put("bootstrapServers", v));

        return config;
    }

    private Map<Long, InputAdapterEntity> loadInputAdapters() {
        return inputAdapterRepository.findAll().stream()
                .collect(Collectors.toMap(InputAdapterEntity::getId, a -> a));
    }
}
