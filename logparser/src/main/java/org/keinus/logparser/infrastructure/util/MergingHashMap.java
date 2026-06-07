package org.keinus.logparser.infrastructure.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 특정 키의 값과 'null' 키(전역 값)의 값을 병합하여 반환하는 특수한 HashMap 유사 자료구조입니다.
 * <p>
 * 이 클래스는 '하나의 키에 여러 값을 매핑'하고, '전역적으로 적용되는 기본값'을 관리하는
 * 시나리오를 위해 설계되었습니다. 예를 들어, 특정 메시지 타입에 대한 처리기와 모든 메시지 타입에
 * 공통으로 적용되는 처리기를 함께 조회하는 데 유용합니다.
 * <p>
 * 주요 특징:
 * <ul>
 *     <li><b>값 병합 조회:</b> {@code get(key)} 호출 시, 해당 {@code key}에 매핑된 리스트와
 *         {@code null} 키에 매핑된 리스트가 병합된 새로운 리스트를 반환합니다.</li>
 *     <li><b>Null 키 지원:</b> {@code null}을 키로 사용하여 전역 또는 기본 값 목록을 관리합니다.</li>
 *     <li><b>다중 값 저장:</b> 각 키는 값의 리스트({@code ArrayList<T>})를 가집니다.
 *         {@code put(key, value)}은 해당 키의 리스트에 값을 추가합니다.</li>
 *     <li><b>크기 제한:</b> 최대 크기를 초과하면 LRU(Least Recently Used) 전략으로 가장 오래된 키를 제거합니다.</li>
 * </ul>
 *
 * @param <T> 리스트에 저장될 요소의 타입
 */
public class MergingHashMap<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(MergingHashMap.class);
    private static final int DEFAULT_MAX_SIZE = 10000;

    // 일반 키(null이 아닌 키)와 값(ArrayList)을 저장하는 내부 맵 (LRU 지원)
    private final Map<String, ArrayList<T>> internalMap;

    // null 키에 해당하는 값(ArrayList)을 저장하는 리스트
    private final ArrayList<T> nullKeyValueList;
    
    // 조회 성능 최적화를 위한 캐시 (변경 시 초기화)
    private final Map<String, List<T>> queryCache = new ConcurrentHashMap<>();

    // 최대 크기 제한
    private final int maxSize;

    // 제거된 엔트리 수 추적
    private long evictedEntries = 0;

    /**
     * MergingHashMap의 새 인스턴스를 생성합니다 (기본 최대 크기: 10000).
     */
    public MergingHashMap() {
        this(DEFAULT_MAX_SIZE);
    }

    /**
     * 지정된 최대 크기로 MergingHashMap의 새 인스턴스를 생성합니다.
     *
     * @param maxSize 맵이 보유할 수 있는 최대 키 개수 (0 이하면 무제한)
     */
    public MergingHashMap(int maxSize) {
        this.maxSize = maxSize > 0 ? maxSize : Integer.MAX_VALUE;
        // LinkedHashMap을 LRU 모드로 사용하지 않음 (accessOrder = false)
        // accessOrder=true일 경우 get() 호출 시에도 구조가 변경되어 멀티스레드 환경에서 안전하지 않음 (ParseService 등에서 문제 발생)
        this.internalMap = new LinkedHashMap<String, ArrayList<T>>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ArrayList<T>> eldest) {
                boolean shouldRemove = size() > MergingHashMap.this.maxSize;
                if (shouldRemove) {
                    evictedEntries++;
                    // LRU로 제거 시 캐시 전체 초기화 (보수적 접근)
                    queryCache.clear();
                    LOGGER.debug("Evicting eldest entry: {} (total evictions: {})",
                                eldest.getKey(), evictedEntries);
                }
                return shouldRemove;
            }
        };
        this.nullKeyValueList = new ArrayList<>();
        LOGGER.info("MergingHashMap initialized with max size: {}", this.maxSize);
    }

    /**
     * 지정된 키에 지정된 값을 추가합니다.
     * 키가 null이면 null 키 전용 리스트에 값을 추가합니다.
     * 키가 null이 아니면 해당 키의 리스트에 값을 추가합니다. 리스트가 없으면 새로 생성합니다.
     *
     * @param key   값을 추가할 키 (null 가능)
     * @param value 추가할 값
     */
    public void put(String key, T value) {
        // 변경 발생 시 캐시 초기화
        queryCache.clear();
        
        if (key == null) {
            this.nullKeyValueList.add(value);
        } else {
            this.internalMap.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
    }

    /**
     * 지정된 키와 연관된 값 리스트를 반환합니다.
     * 이 리스트는 지정된 키의 값들과 null 키의 값들이 병합된 결과입니다.
     * 만약 지정된 키가 맵에 없으면 null 키의 값들만 포함된 리스트가 반환됩니다.
     *
     * @param key 조회할 키 (null 가능)
     * @return 지정된 키의 값들과 null 키의 값들이 병합된 새로운 ArrayList.
     *         지정된 키와 null 키 모두에 값이 없으면 빈 리스트를 반환합니다.
     */
    public List<T> get(String key) {
        String cacheKey = (key == null) ? "NULL_KEY_MARKER" : key;
        
        return queryCache.computeIfAbsent(cacheKey, k -> {
            List<T> specificList = null;
            if (key != null) {
                specificList = this.internalMap.get(key);
            }

            boolean hasSpecific = (specificList != null && !specificList.isEmpty());
            boolean hasGlobal = !this.nullKeyValueList.isEmpty();

            // 1. 둘 다 없는 경우
            if (!hasSpecific && !hasGlobal) {
                return Collections.emptyList();
            }

            // 2. Specific만 있는 경우 (복사 없이 반환)
            if (hasSpecific && !hasGlobal) {
                return specificList;
            }

            // 3. Global만 있는 경우 (복사 없이 반환)
            if (!hasSpecific && hasGlobal) {
                return this.nullKeyValueList;
            }

            // 4. 둘 다 있는 경우 (병합 복사 발생)
            ArrayList<T> mergedList = new ArrayList<>(specificList.size() + this.nullKeyValueList.size());
            mergedList.addAll(specificList);
            mergedList.addAll(this.nullKeyValueList);
            return mergedList;
        });
    }

    /**
     * 지정된 키에 대한 매핑을 제거합니다.
     *
     * @param key 제거할 매핑의 키 (null 가능)
     * @return 제거되기 전 키와 연관된 값 리스트 (null 키의 값은 포함되지 않음).
     *         키가 null이면 null 키의 리스트가 비워지고 이전 리스트의 복사본이 반환됩니다.
     *         키가 없었으면 null을 반환합니다.
     */
    public List<T> remove(String key) {
        // 변경 발생 시 캐시 초기화
        queryCache.clear();
        
        if (key == null) {
            if (nullKeyValueList.isEmpty()) {
                return nullKeyValueList;
            }
            ArrayList<T> previousNullList = new ArrayList<>(this.nullKeyValueList);
            this.nullKeyValueList.clear();
            return previousNullList;
        } else {
            return this.internalMap.remove(key);
        }
    }

     /**
     * 이 맵의 모든 값 리스트(null 키 포함)에서 지정된 값의 모든 인스턴스를 제거합니다.
     * ArrayList.remove(Object) 메소드를 사용하여 값을 비교하고 제거합니다.
     * 1. null 키의 리스트에서 값 제거
     * remove(Object)는 요소가 제거되면 true를 반환합니다.
     * 리스트 내에 동일한 값이 여러 개 있을 수 있으므로, 없어질 때까지 반복 제거합니다.
     * 2. internalMap의 모든 값(ArrayList) 리스트에서 값 제거
     * ConcurrentModificationException을 피하기 위해 map의 value 컬렉션을 직접 순회합니다.
     * 각 리스트 내부에서 remove(value)를 호출하는 것은 안전합니다.
     * @param value 제거할 값 (null 허용)
     * @return 이 호출의 결과로 하나 이상의 요소가 제거되었으면 true, 그렇지 않으면 false
     */
    public boolean removeValue(T value) {
        boolean removed = false;

        while (this.nullKeyValueList.remove(value)) {
            removed = true; // 하나라도 제거되면 true로 설정
        }

        for (ArrayList<T> list : this.internalMap.values()) {
            while (list.remove(value)) {
                removed = true; // 하나라도 제거되면 true로 설정
            }
        }
        
        if (removed) {
            queryCache.clear();
        }
        
        return removed;
    }

    /**
     * 맵이 지정된 키를 포함하는지 여부를 반환합니다.
     *
     * @param key 확인할 키 (null 가능)
     * @return 키가 존재하면 true, 그렇지 않으면 false
     */
    public boolean containsKey(String key) {
        if (key == null) {
            return !this.nullKeyValueList.isEmpty();
        } else {
            return this.internalMap.containsKey(key);
        }
    }

    /**
     * 맵의 모든 매핑을 제거합니다.
     */
    public void clear() {
        queryCache.clear();
        this.internalMap.clear();
        this.nullKeyValueList.clear();
    }

    /**
     * 맵이 비어있는지 여부를 반환합니다. (null 키 포함)
     *
     * @return 일반 맵과 null 키 리스트가 모두 비어있으면 true, 아니면 false
     */
    public boolean isEmpty() {
        return this.internalMap.isEmpty() && this.nullKeyValueList.isEmpty();
    }

    /**
     * 맵의 키 개수(null 키 포함)를 반환합니다.
     *
     * @return 맵에 있는 키의 총 개수
     */
    public int size() {
        int size = this.internalMap.size();
        if (!this.nullKeyValueList.isEmpty()) {
            size++; // null 키도 하나의 키로 간주
        }
        return size;
    }

    /**
     * 맵의 모든 키를 반환합니다 (null 키 포함).
     *
     * @return 모든 키를 포함한 Set
     */
    public Set<String> getAllKeys() {
        Set<String> keys = new HashSet<>(this.internalMap.keySet());
        if (!this.nullKeyValueList.isEmpty()) {
            keys.add(null);
        }
        return keys;
    }

    /**
     * 최대 크기 제한을 반환합니다.
     *
     * @return 맵의 최대 크기
     */
    public int getMaxSize() {
        return this.maxSize;
    }

    /**
     * LRU eviction으로 제거된 총 엔트리 수를 반환합니다.
     *
     * @return 제거된 엔트리 수
     */
    public long getEvictedEntries() {
        return this.evictedEntries;
    }

    /**
     * 현재 사용률을 백분율로 반환합니다.
     *
     * @return 사용률 (0.0 ~ 100.0)
     */
    public double getUtilization() {
        if (maxSize == Integer.MAX_VALUE) {
            return 0.0;
        }
        return (double) size() / maxSize * 100.0;
    }

    /**
     * 맵의 상태 정보를 문자열로 반환합니다.
     *
     * @return 상태 정보 문자열
     */
    public String getStats() {
        return String.format("MergingHashMap{size=%d, maxSize=%d, utilization=%.1f%%, evicted=%d}",
                size(), maxSize, getUtilization(), evictedEntries);
    }
}