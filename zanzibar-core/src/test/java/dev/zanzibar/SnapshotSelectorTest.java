package dev.zanzibar;

import dev.zanzibar.engine.SnapshotSelector;
import dev.zanzibar.model.Zookie;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotSelectorTest {

    @Test
    void withoutAZookieTheRevisionIsRoundedDown() {
        SnapshotSelector selector = new SnapshotSelector(10);
        assertEquals(20, selector.select(null, 27));
        assertEquals(20, selector.select(null, 20));
        assertEquals(0, selector.select(null, 9));
    }

    @Test
    void quantumOfOneAlwaysGivesTheLatest() {
        SnapshotSelector selector = new SnapshotSelector(1);
        assertEquals(27, selector.select(null, 27));
    }

    @Test
    void anOldZookieSharesTheRoundedRevision() {
        SnapshotSelector selector = new SnapshotSelector(10);
        assertEquals(20, selector.select(new Zookie(13), 27));
    }

    @Test
    void aRecentZookieIsNeverRoundedBelowItself() {
        SnapshotSelector selector = new SnapshotSelector(10);
        assertEquals(25, selector.select(new Zookie(25), 27));
    }

    @Test
    void aZookieNewerThanTheStoreIsAnError() {
        SnapshotSelector selector = new SnapshotSelector(10);
        assertThrows(IllegalArgumentException.class, () -> selector.select(new Zookie(28), 27));
    }
}
