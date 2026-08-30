package org.keinus.logparser.domain.parse.service;

import java.lang.reflect.InvocationTargetException;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.keinus.logparser.domain.configuration.model.ParserAdapterConfig;
import org.keinus.logparser.domain.configuration.service.DatabaseConfigLoader;
import org.keinus.logparser.infrastructure.util.MergingHashMap;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.parse.model.IParser;
import org.keinus.logparser.domain.parse.model.RegexParser;
import org.springframework.stereotype.Service;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;

/**
 * 원본 로그 텍스트를 구조화된 데이터(Map)로 파싱하는 서비스 클래스입니다.
 */
@Service
public class ParseService {
    private static final Logger LOGGER = LoggerFactory.getLogger( ParseService.class );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private record ParserBinding(IParser parser, boolean continueOnFailure, String parserType) {}

    /**
     * A parser instance bound to one processing step.  The parser implementation
     * still receives a LogEvent, which keeps the existing parser contract intact.
     */
    public static final class ParserStep {
        private final IParser parser;
        private final String parserType;
        private final String sourceField;
        private final boolean continueOnFailure;

        private ParserStep(IParser parser, String parserType, String sourceField, boolean continueOnFailure) {
            this.parser = parser;
            this.parserType = parserType;
            this.sourceField = sourceField;
            this.continueOnFailure = continueOnFailure;
        }

        public boolean continueOnFailure() {
            return continueOnFailure;
        }

        public String parserType() {
            return parserType;
        }

        public boolean execute(LogEvent logEvent) {
            if (sourceField == null || sourceField.isBlank()) {
                return parser.parse(logEvent);
            }

            Object sourceValue = logEvent.getField(sourceField);
            if (sourceValue == null) {
                return false;
            }

            Map<String, Object> parsedFields = parseSourceValue(parser, sourceValue, logEvent);
            if (parsedFields == null) {
                return false;
            }

            // A parser configured with sourceField replaces that field with the
            // parser result.  This keeps the parsed structure attached to the
            // input field and removes the original scalar/list value.
            logEvent.removeField(sourceField);
            logEvent.setField(sourceField, parsedFields);
            return true;
        }
    }

    /** Keep test inputs and runtime source fields on the same conversion path. */
    private static Map<String, Object> parseSourceValue(IParser parser, Object sourceValue, LogEvent target) {
        Iterable<?> values = parser instanceof RegexParser && sourceValue instanceof Iterable<?> items
                ? items : Collections.singletonList(sourceValue);
        Map<String, Object> parsedFields = new HashMap<>();
        boolean matched = false;
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text;
            try {
                text = value instanceof String ? (String) value
                        : value instanceof Map<?, ?> || value instanceof Iterable<?>
                                ? OBJECT_MAPPER.writeValueAsString(value) : String.valueOf(value);
            } catch (Exception e) {
                target.markAsError("Failed to serialize parser input: " + e.getMessage());
                return null;
            }
            LogEvent sourceEvent = new LogEvent(text, target.getSourceHost(), target.getMessageType());
            boolean parsed = parser.parse(sourceEvent);
            if (sourceEvent.hasError()) {
                target.markAsError(sourceEvent.getProcessingError());
                return null;
            }
            if (parsed) {
                matched = true;
                parsedFields.putAll(sourceEvent.getFields());
            }
        }
        return matched ? parsedFields : null;
    }

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

    /** Creates one initialized parser step for the unified processing chain. */
    public ParserStep createStep(ParserAdapterConfig config) {
        IParser parserInterface = loadLibrary(config.getType());
        if (parserInterface == null) {
            return null;
        }
        parserInterface.init(config.getParam());
        return new ParserStep(
                parserInterface,
                config.getType(),
                config.getSourceField() == null ? null : config.getSourceField().trim(),
                Boolean.TRUE.equals(config.getContinueOnFailure())
        );
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
    public java.util.Map<String, Object> testParser(String parserType, Object param, Object sampleData) {
        if (sampleData == null) {
            throw new IllegalArgumentException("Sample data is required");
        }
        IParser parser = loadLibrary(parserType);
        if (parser == null) {
            throw new IllegalArgumentException("Invalid parser type: " + parserType);
        }
        
        try {
            parser.init(param);
        } catch (Exception e) {
             throw new IllegalArgumentException("Failed to initialize parser: " + e.getMessage(), e);
        }
        
        LogEvent event = new LogEvent("", "test-host", "test-type");
        Map<String, Object> parsedFields = parseSourceValue(parser, sampleData, event);
        
        if (event.hasError()) {
             throw new RuntimeException("Parsing failed: " + event.getProcessingError());
        }
        
        if (parsedFields == null) {
            throw new IllegalArgumentException("Parser did not match the sample data");
        }
        
        return parsedFields;
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
