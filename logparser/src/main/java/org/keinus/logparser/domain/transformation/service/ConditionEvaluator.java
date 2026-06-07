package org.keinus.logparser.domain.transformation.service;

import java.util.Queue;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;

@Service
public class ConditionEvaluator {
    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);
    private static final int DEFAULT_MAX_CACHE_SIZE = 1024;

    private final ExpressionParser parser;
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();
    private final Queue<String> insertionOrder = new ConcurrentLinkedQueue<>();
    private final int maxCacheSize;

    public ConditionEvaluator() {
        this(new SpelExpressionParser(), DEFAULT_MAX_CACHE_SIZE);
    }

    public ConditionEvaluator(int maxCacheSize) {
        this(new SpelExpressionParser(), maxCacheSize);
    }

    private ConditionEvaluator(ExpressionParser parser, int maxCacheSize) {
        this.parser = parser;
        this.maxCacheSize = Math.max(1, maxCacheSize);
    }

    public boolean evaluate(String conditionExpression, Map<String, Object> data) {
        if (conditionExpression == null || conditionExpression.trim().isEmpty()) {
            // Empty condition means "Always apply" or "Catch-all".
            return true;
        }

        try {
            StandardEvaluationContext context = new StandardEvaluationContext();
            context.setVariables(data);
            
            // Allow accessing map keys directly as variables is tricky in SpEL without # prefix.
            // But we can set the root object to the map.
            context.setRootObject(data);
            
            // However, SpEL map access is usually ['key'].
            // To support "dst_port == 80", we ideally want property access.
            // StandardEvaluationContext with MapAccessor allows this.
            context.addPropertyAccessor(new org.springframework.context.expression.MapAccessor());

            Expression exp = expressionCache.get(conditionExpression);
            if (exp == null) {
                Expression parsedExpression = parser.parseExpression(conditionExpression);
                Expression previous = expressionCache.putIfAbsent(conditionExpression, parsedExpression);
                exp = previous != null ? previous : parsedExpression;
                if (previous == null) {
                    insertionOrder.add(conditionExpression);
                    trimCacheIfNeeded();
                }
            }
                
            Boolean result = exp.getValue(context, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.warn("Failed to evaluate condition: '{}' with data: {}", conditionExpression, data, e);
            return false;
        }
    }

    public void clearCache() {
        expressionCache.clear();
        insertionOrder.clear();
    }

    public int getCachedExpressionCount() {
        return expressionCache.size();
    }

    private void trimCacheIfNeeded() {
        while (expressionCache.size() > maxCacheSize) {
            String oldest = insertionOrder.poll();
            if (oldest == null) {
                return;
            }
            expressionCache.remove(oldest);
        }
    }
}
