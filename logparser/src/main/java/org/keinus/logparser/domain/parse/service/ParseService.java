package org.keinus.logparser.domain.parse.service;

import java.lang.reflect.InvocationTargetException;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.keinus.logparser.domain.configuration.model.ParserAdapterConfig;
import org.keinus.logparser.domain.configuration.service.DatabaseConfigLoader;
import org.keinus.logparser.infrastructure.util.MergingHashMap;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.parse.model.IParser;
import org.springframework.stereotype.Service;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;

/**
 * 원본 로그 텍스트를 구조화된 데이터(Map)로 파싱하는 서비스 클래스입니다.
 */
@Service
public class ParseService {
    private static final Logger LOGGER = LoggerFactory.getLogger( ParseService.class );

    private record ParserBinding(IParser parser, boolean continueOnFailure, String parserType) {}

    private MergingHashMap<ParserBinding> parsers = new MergingHashMap<>();
    private final DatabaseConfigLoader databaseConfigLoader;

    public ParseService(ApplicationProperties applicationProperties, DatabaseConfigLoader databaseConfigLoader) {
        this.databaseConfigLoader = databaseConfigLoader;
        this.parsers = buildParsers(applicationProperties.getParser());
    }

    /**
     * 데이터베이스에서 파서 설정을 다시 로드합니다.
     */
    public synchronized void reload() {
        LOGGER.info("Reloading parsers from database");

        try {
            DatabaseConfigLoader.PipelineConfiguration config = databaseConfigLoader.loadConfiguration();
            reload(config.getParser());
        } catch (Exception e) {
            LOGGER.error("Failed to reload parsers", e);
            throw new RuntimeException("Failed to reload parsers", e);
        }
    }

    public synchronized void reload(List<ParserAdapterConfig> parserList) {
        this.parsers = buildParsers(parserList);
        LOGGER.info("Parser reload completed: {} parsers loaded", parserList == null ? 0 : parserList.size());
    }

    private IParser loadLibrary(String parserClassName) {
        String className = "org.keinus.logparser.domain.parse.model." + parserClassName;
        Class<?> testClass;
        try {
            testClass = Class.forName(className);
        } catch (ClassNotFoundException e) {
            LOGGER.error(className + " not found", e);
            return null;
        }
        if (testClass == null || !IParser.class.isAssignableFrom(testClass)) {
            LOGGER.error("{} is not a valid parser class", className);
            return null;
        }
        IParser parserInterface;
        try {
            parserInterface = (IParser) testClass.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException | NoSuchMethodException | SecurityException e) {
            LOGGER.error("{} can not call instantiated", className);
            return null;
        }
        return parserInterface;
    }

    /**
     * LogEvent를 파싱합니다.
     */
    public boolean parse(LogEvent logEvent) {
        String messageType = logEvent.getMessageType();
        List<ParserBinding> parserList = parsers.get(messageType);

        if (parserList.isEmpty()) {
            return true;
        }

        for (ParserBinding binding : parserList) {
            boolean parsed = false;
            try {
                parsed = binding.parser().parse(logEvent);
            } catch (Exception e) {
                LOGGER.warn("Parser {} failed for messageType {}: {}",
                        binding.parserType(), messageType, e.getMessage(), e);
                logEvent.markAsError("Parsing failed: " + e.getMessage());
            }

            if (parsed) {
                return true;
            }

            if (!binding.continueOnFailure()) {
                return false;
            }
        }
        return false;
    }

    /**
     * Tests a parser with the given configuration and sample data.
     */
    public java.util.Map<String, Object> testParser(String parserType, Object param, String sampleData) {
        IParser parser = loadLibrary(parserType);
        if (parser == null) {
            throw new IllegalArgumentException("Invalid parser type: " + parserType);
        }
        
        try {
            parser.init(param);
        } catch (Exception e) {
             throw new IllegalArgumentException("Failed to initialize parser: " + e.getMessage(), e);
        }
        
        LogEvent event = new LogEvent(sampleData, "test-host", "test-type");
        boolean success = parser.parse(event);
        
        if (event.hasError()) {
             throw new RuntimeException("Parsing failed: " + event.getProcessingError());
        }
        
        if (!success) {
            return java.util.Collections.emptyMap();
        }
        
        return event.getFields();
    }

    private MergingHashMap<ParserBinding> buildParsers(List<ParserAdapterConfig> parserList) {
        MergingHashMap<ParserBinding> newParsers = new MergingHashMap<>();
        if (parserList == null) {
            return newParsers;
        }

        List<ParserAdapterConfig> sortedParsers = parserList.stream()
                .sorted(Comparator
                        .comparing((ParserAdapterConfig parser) -> parser.getPriority() == null ? Integer.MAX_VALUE : parser.getPriority())
                        .thenComparing(parser -> parser.getId() == null ? Long.MAX_VALUE : parser.getId()))
                .toList();

        for (ParserAdapterConfig parser : sortedParsers) {
            String parserType = parser.getType();
            IParser parserInterface = loadLibrary(parserType);
            if (parserInterface == null) {
                continue;
            }
            parserInterface.init(parser.getParam());
            String msgType = parser.getMessagetype();
            boolean continueOnFailure = Boolean.TRUE.equals(parser.getContinueOnFailure());
            newParsers.put(msgType, new ParserBinding(parserInterface, continueOnFailure, parserType));
            LOGGER.info("Message Parser registered {} (priority={}, continueOnFailure={})",
                    parserType,
                    parser.getPriority(),
                    continueOnFailure);
        }

        return newParsers;
    }
}
