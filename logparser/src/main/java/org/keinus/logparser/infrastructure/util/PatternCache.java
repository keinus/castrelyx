package org.keinus.logparser.infrastructure.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Regex 패턴을 캐싱하여 Pattern.compile() 호출로 인한 CPU 오버헤드를 감소시킵니다.
 * <p>
 * Pattern.compile()은 비용이 높은 연산이므로, 동일한 정규식 패턴을 반복적으로
 * 컴파일하는 것은 성능에 부정적인 영향을 미칩니다. 이 클래스는 컴파일된 패턴을
 * 캐시하여 재사용함으로써 성능을 향상시킵니다.
 * <p>
 * 주요 특징:
 * <ul>
 *     <li>Thread-safe: ConcurrentHashMap을 사용하여 멀티스레드 환경에서 안전</li>
 *     <li>자동 캐싱: 동일한 패턴 문자열에 대해 한 번만 컴파일</li>
 *     <li>메모리 관리: 최대 크기 제한으로 메모리 보호</li>
 *     <li>통계 추적: 캐시 히트/미스 및 제거 횟수 추적</li>
 * </ul>
 *
 * @see java.util.regex.Pattern
 */
public class PatternCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(PatternCache.class);
    private static final int DEFAULT_MAX_SIZE = 1000;

    private final ConcurrentHashMap<String, Pattern> cache;
    private final int maxSize;

    // 통계 추적
    private long cacheHits = 0;
    private long cacheMisses = 0;
    private long evictions = 0;

    /**
     * 기본 최대 크기(1000)로 PatternCache를 생성합니다.
     */
    public PatternCache() {
        this(DEFAULT_MAX_SIZE);
    }

    /**
     * 지정된 최대 크기로 PatternCache를 생성합니다.
     *
     * @param maxSize 캐시할 최대 패턴 개수
     */
    public PatternCache(int maxSize) {
        this.maxSize = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
        this.cache = new ConcurrentHashMap<>(Math.min(16, this.maxSize));
        LOGGER.info("PatternCache initialized with max size: {}", this.maxSize);
    }

    /**
     * 정규식 패턴을 컴파일하여 반환합니다. 캐시에 있으면 캐시된 패턴을 반환합니다.
     *
     * @param regex 정규식 패턴 문자열
     * @return 컴파일된 Pattern 객체
     * @throws java.util.regex.PatternSyntaxException 정규식이 잘못된 경우
     */
    public Pattern compile(String regex) {
        if (regex == null) {
            throw new IllegalArgumentException("Regex pattern cannot be null");
        }

        Pattern pattern = cache.get(regex);
        if (pattern != null) {
            cacheHits++;
            LOGGER.trace("Cache hit for pattern: {}", regex);
            return pattern;
        }

        cacheMisses++;
        LOGGER.trace("Cache miss for pattern: {}", regex);

        // 캐시 크기 제한 체크
        if (cache.size() >= maxSize) {
            evictOldestEntry();
        }

        pattern = Pattern.compile(regex);
        cache.put(regex, pattern);
        LOGGER.debug("Compiled and cached new pattern: {}", regex);

        return pattern;
    }

    /**
     * 플래그를 지정하여 정규식 패턴을 컴파일합니다.
     *
     * @param regex 정규식 패턴 문자열
     * @param flags Pattern 플래그 (예: Pattern.CASE_INSENSITIVE)
     * @return 컴파일된 Pattern 객체
     * @throws java.util.regex.PatternSyntaxException 정규식이 잘못된 경우
     */
    public Pattern compile(String regex, int flags) {
        if (regex == null) {
            throw new IllegalArgumentException("Regex pattern cannot be null");
        }

        // 플래그를 포함한 캐시 키 생성
        String cacheKey = regex + ":" + flags;

        Pattern pattern = cache.get(cacheKey);
        if (pattern != null) {
            cacheHits++;
            LOGGER.trace("Cache hit for pattern with flags: {}", regex);
            return pattern;
        }

        cacheMisses++;
        LOGGER.trace("Cache miss for pattern with flags: {}", regex);

        // 캐시 크기 제한 체크
        if (cache.size() >= maxSize) {
            evictOldestEntry();
        }

        pattern = Pattern.compile(regex, flags);
        cache.put(cacheKey, pattern);
        LOGGER.debug("Compiled and cached new pattern with flags: {}", regex);

        return pattern;
    }

    /**
     * 가장 오래된 엔트리를 제거합니다.
     * ConcurrentHashMap은 순서를 보장하지 않으므로, 임의의 엔트리를 제거합니다.
     */
    private void evictOldestEntry() {
        if (!cache.isEmpty()) {
            String firstKey = cache.keys().nextElement();
            cache.remove(firstKey);
            evictions++;
            LOGGER.debug("Evicted pattern from cache (total evictions: {})", evictions);
        }
    }

    /**
     * 캐시를 비웁니다.
     */
    public void clear() {
        cache.clear();
        LOGGER.info("Pattern cache cleared");
    }

    /**
     * 캐시 통계를 초기화합니다.
     * 캐시 히트, 미스, 제거 카운터를 0으로 리셋합니다.
     * 캐시된 패턴은 그대로 유지됩니다.
     */
    public void resetStatistics() {
        cacheHits = 0;
        cacheMisses = 0;
        evictions = 0;
        LOGGER.info("Pattern cache statistics reset");
    }

    /**
     * 캐시된 패턴 개수를 반환합니다.
     *
     * @return 캐시 크기
     */
    public int size() {
        return cache.size();
    }

    /**
     * 캐시 히트 횟수를 반환합니다.
     *
     * @return 캐시 히트 횟수
     */
    public long getCacheHits() {
        return cacheHits;
    }

    /**
     * 캐시 미스 횟수를 반환합니다.
     *
     * @return 캐시 미스 횟수
     */
    public long getCacheMisses() {
        return cacheMisses;
    }

    /**
     * 캐시 히트율을 백분율로 반환합니다.
     *
     * @return 히트율 (0.0 ~ 100.0)
     */
    public double getHitRate() {
        long total = cacheHits + cacheMisses;
        if (total == 0) {
            return 0.0;
        }
        return (double) cacheHits / total * 100.0;
    }

    /**
     * 제거된 엔트리 수를 반환합니다.
     *
     * @return 제거된 엔트리 수
     */
    public long getEvictions() {
        return evictions;
    }

    /**
     * 캐시 통계를 문자열로 반환합니다.
     *
     * @return 통계 정보 문자열
     */
    public String getStats() {
        return String.format("PatternCache{size=%d/%d, hits=%d, misses=%d, hitRate=%.1f%%, evictions=%d}",
                size(), maxSize, cacheHits, cacheMisses, getHitRate(), evictions);
    }

    // Singleton 인스턴스 (선택적 사용)
    private static volatile PatternCache instance;

    /**
     * 싱글톤 인스턴스를 반환합니다.
     *
     * @return PatternCache 인스턴스
     */
    public static PatternCache getInstance() {
        if (instance == null) {
            synchronized (PatternCache.class) {
                if (instance == null) {
                    instance = new PatternCache();
                }
            }
        }
        return instance;
    }
}
