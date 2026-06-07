package org.keinus.logparser.infrastructure.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MergingHashMapTest {

    private MergingHashMap<String> map;

    @BeforeEach
    void setUp() {
        map = new MergingHashMap<>();
    }

    @Test
    void testPutAndGetWithCache() {
        // 1. Put specific and global values
        map.put("key1", "value1");
        map.put(null, "global");

        // 2. First get (cache miss, compute)
        List<String> list1 = map.get("key1");
        assertEquals(2, list1.size());
        assertTrue(list1.contains("value1"));
        assertTrue(list1.contains("global"));

        // 3. Second get (cache hit)
        List<String> list2 = map.get("key1");
        assertEquals(list1, list2); // Should be same list instance if cached (or at least equal content)
        
        // 4. Update map (should invalidate cache)
        map.put("key1", "value2");
        
        // 5. Get again (recomputed)
        List<String> list3 = map.get("key1");
        assertEquals(3, list3.size());
        assertTrue(list3.contains("value2"));
    }

    @Test
    void testRemoveInvalidatesCache() {
        map.put("key1", "value1");
        map.get("key1"); // warm cache

        map.remove("key1");
        List<String> list = map.get("key1"); // should be empty or just global
        assertTrue(list.isEmpty());
    }
}
