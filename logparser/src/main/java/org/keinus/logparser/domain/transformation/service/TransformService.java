package org.keinus.logparser.domain.transformation.service;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.keinus.logparser.domain.configuration.model.TransformConfig;
import org.keinus.logparser.domain.configuration.service.DatabaseConfigLoader;
import org.keinus.logparser.domain.transformation.model.ITransform;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;
import org.springframework.stereotype.Service;

/**
 * 파싱된 메시지 데이터에 대해 다양한 변환 작업을 수행하는 서비스 클래스입니다.
 */
@Service
public class TransformService {
    private static final Logger LOGGER = LoggerFactory.getLogger( TransformService.class );

    private Map<String, List<ITransform>> transformer = new HashMap<>();
    private final DatabaseConfigLoader databaseConfigLoader;

    private ITransform loadLibrary(String className) {
        if ("Structure".equals(className)) {
            LOGGER.info("Skipping legacy Structure transform registration because structured transformation runs centrally");
            return null;
        }

        String classFullName = "org.keinus.logparser.domain.transformation.model." + className;
        Class<?> testClass;
        try {
            testClass = Class.forName(classFullName);
        } catch (ClassNotFoundException e) {
            LOGGER.error(classFullName + " not found", e);
            return null;
        }
        if (testClass == null || !ITransform.class.isAssignableFrom(testClass)) {
            LOGGER.error("{} is not a valid transform class", classFullName);
            return null;
        }
        ITransform transformInterface;
        try {
            transformInterface = (ITransform) testClass.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException | NoSuchMethodException | SecurityException e) {
            LOGGER.error("{} can not call instantiated", classFullName);
            return null;
        }
        if (transformInterface == null || !ITransform.class.isAssignableFrom(transformInterface.getClass())) {
            LOGGER.error("{} is not a valid transform class", classFullName);
            return null;
        }
        return transformInterface;
    }

    public TransformService(ApplicationProperties applicationProperties, DatabaseConfigLoader databaseConfigLoader) {
        this.databaseConfigLoader = databaseConfigLoader;
        this.transformer = buildTransformers(applicationProperties.getTransform());
    }

    /**
     * 데이터베이스에서 변환 설정을 다시 로드합니다.
     */
    public synchronized void reload() {
        LOGGER.info("Reloading transforms from database");

        try {
            DatabaseConfigLoader.PipelineConfiguration config = databaseConfigLoader.loadConfiguration();
            reload(config.getTransform());
        } catch (Exception e) {
            LOGGER.error("Failed to reload transforms", e);
            throw new RuntimeException("Failed to reload transforms", e);
        }
    }

    public synchronized void reload(List<TransformConfig> transformList) {
        this.transformer = buildTransformers(transformList);
        LOGGER.info("Transform reload completed: {} transforms loaded", transformList == null ? 0 : transformList.size());
    }

    /**
     * LogEvent를 변환합니다.
     */
    public boolean transform(LogEvent logEvent) {
        String messageType = logEvent.getMessageType();
        for(ITransform trans : transformer.getOrDefault(messageType, new ArrayList<>())) {
            if(!trans.transform(logEvent)) {
                return false;
            }
        }
        return true;
    }

    private Map<String, List<ITransform>> buildTransformers(List<TransformConfig> transformList) {
        Map<String, List<ITransform>> newTransformer = new HashMap<>();
        if (transformList == null) {
            return newTransformer;
        }

        List<TransformConfig> sortedTransforms = transformList.stream()
                .sorted(Comparator
                        .comparing((TransformConfig transform) -> transform.getPriority() == null ? Integer.MAX_VALUE : transform.getPriority())
                        .thenComparing(transform -> transform.getId() == null ? Long.MAX_VALUE : transform.getId()))
                .toList();

        for (TransformConfig trans : sortedTransforms) {
            ITransform transformInterface = loadLibrary(trans.getType());
            if (transformInterface == null) {
                continue;
            }
            transformInterface.init(trans.getParam());
            String msgType = trans.getMessagetype();
            newTransformer.computeIfAbsent(msgType, k -> new ArrayList<>());
            newTransformer.get(msgType).add(transformInterface);
            LOGGER.info("Transform registered: {} (priority={})", trans.getType(), trans.getPriority());
        }

        return newTransformer;
    }
}
