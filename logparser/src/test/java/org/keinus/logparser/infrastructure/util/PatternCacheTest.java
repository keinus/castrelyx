package org.keinus.logparser.infrastructure.util;

import org.junit.jupiter.api.Test;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatternCacheTest {

    @Test
    void testCompile() {
        PatternCache cache = new PatternCache(10);
        Pattern p1 = cache.compile("abc");
        Pattern p2 = cache.compile("abc");
        
        assertThat(p1).isSameAs(p2);
        assertThat(cache.getCacheHits()).isEqualTo(1);
        assertThat(cache.getCacheMisses()).isEqualTo(1);
    }

    @Test
    void testCompileWithFlags() {
        PatternCache cache = new PatternCache(10);
        Pattern p1 = cache.compile("abc", Pattern.CASE_INSENSITIVE);
        Pattern p2 = cache.compile("abc", Pattern.CASE_INSENSITIVE);
        
        assertThat(p1).isSameAs(p2);
        assertThat(p1.flags()).isEqualTo(Pattern.CASE_INSENSITIVE);
    }

    @Test
    void testEviction() {
        PatternCache cache = new PatternCache(2);
        cache.compile("a");
        cache.compile("b");
        cache.compile("c");
        
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.getEvictions()).isEqualTo(1);
    }

    @Test
    void testClearAndReset() {
        PatternCache cache = new PatternCache(10);
        cache.compile("a");
        cache.clear();
        assertThat(cache.size()).isEqualTo(0);
        
        cache.compile("a");
        cache.resetStatistics();
        assertThat(cache.getCacheHits()).isEqualTo(0);
        assertThat(cache.getCacheMisses()).isEqualTo(0);
    }

    @Test
    void testGetStats() {
        PatternCache cache = new PatternCache(10);
        cache.compile("a");
        assertThat(cache.getStats()).contains("hits=0", "misses=1");
    }

    @Test
    void testSingleton() {
        PatternCache instance1 = PatternCache.getInstance();
        PatternCache instance2 = PatternCache.getInstance();
        assertThat(instance1).isSameAs(instance2);
    }

    @Test
    void testInvalidRegex() {
        PatternCache cache = new PatternCache();
        assertThatThrownBy(() -> cache.compile("[")).isInstanceOf(java.util.regex.PatternSyntaxException.class);
    }
}
