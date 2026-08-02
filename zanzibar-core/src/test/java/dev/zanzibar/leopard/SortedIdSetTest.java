package dev.zanzibar.leopard;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the sorted-id set and its galloping intersection. */
class SortedIdSetTest {

    private static SortedIdSet set(long... values) {
        List<Long> list = new ArrayList<>();
        for (long v : values) list.add(v);
        return SortedIdSet.of(list);
    }

    @Test
    void deduplicatesAndSorts() {
        SortedIdSet s = set(5, 1, 3, 3, 1);
        assertArrayEquals(new long[]{1, 3, 5}, s.toArray());
        assertEquals(3, s.size());
    }

    @Test
    void containsUsesBinarySearch() {
        SortedIdSet s = set(2, 4, 6, 8, 10);
        assertTrue(s.contains(6));
        assertFalse(s.contains(7));
        assertFalse(s.contains(0));
        assertFalse(s.contains(11));
    }

    @Test
    void emptyNeverIntersects() {
        assertFalse(SortedIdSet.EMPTY.intersects(set(1, 2, 3)));
        assertFalse(set(1, 2, 3).intersects(SortedIdSet.EMPTY));
        assertFalse(SortedIdSet.EMPTY.intersects(SortedIdSet.EMPTY));
    }

    @Test
    void disjointSetsDoNotIntersect() {
        assertFalse(set(1, 3, 5, 7).intersects(set(2, 4, 6, 8)));
    }

    @Test
    void overlappingSetsIntersect() {
        assertTrue(set(1, 3, 5, 7).intersects(set(6, 7, 8)));   // match at 7
        assertTrue(set(1, 3, 5, 7).intersects(set(0, 1)));      // match at 1 (start)
        assertTrue(set(100).intersects(set(1, 50, 100, 200)));  // singleton, gallops far
    }

    @Test
    void intersectionIsSymmetric() {
        SortedIdSet a = set(1, 5, 9, 13, 17, 21);
        SortedIdSet b = set(4, 8, 12, 13, 20);
        assertEquals(a.intersects(b), b.intersects(a));
        assertTrue(a.intersects(b)); // 13
    }

    /**
     * Fuzz the galloping intersection against a trivial HashSet oracle over many
     * random pairs — this is where off-by-one gallop bugs would surface.
     */
    @Test
    void gallopingMatchesNaiveOracle() {
        Random rng = new Random(2024);
        for (int trial = 0; trial < 5000; trial++) {
            Set<Long> a = randomSet(rng, rng.nextInt(40), 60);
            Set<Long> b = randomSet(rng, rng.nextInt(40), 60);
            boolean expected = !Collections.disjoint(a, b);
            boolean actual = SortedIdSet.of(a).intersects(SortedIdSet.of(b));
            assertEquals(expected, actual,
                    () -> "mismatch for a=" + new TreeSet<>(a) + " b=" + new TreeSet<>(b));
        }
    }

    private static Set<Long> randomSet(Random rng, int size, int universe) {
        Set<Long> s = new HashSet<>();
        for (int i = 0; i < size; i++) s.add((long) rng.nextInt(universe));
        return s;
    }
}
