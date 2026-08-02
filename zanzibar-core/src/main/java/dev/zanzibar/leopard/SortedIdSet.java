package dev.zanzibar.leopard;

import java.util.Arrays;
import java.util.Collection;

/**
 * An immutable, sorted, de-duplicated set of {@code long} ids with a fast
 * intersection test — the query-time representation Leopard borrows straight from
 * search-engine posting lists.
 *
 * <p>The interesting operation is {@link #intersects(SortedIdSet)}: rather than a
 * linear merge, it walks the smaller set and <b>gallops</b> (exponential search
 * followed by a bounded binary search) through the larger one, skipping over long
 * runs that cannot contain a match. Cost is {@code O(m * log(n))} worst case for
 * sizes {@code m <= n}, and far less when the sets are clustered — which is the
 * common case for group ids.
 */
public final class SortedIdSet {

    public static final SortedIdSet EMPTY = new SortedIdSet(new long[0]);

    private final long[] ids; // ascending, distinct

    private SortedIdSet(long[] ids) {
        this.ids = ids;
    }

    /** Build from any collection of ids (sorted and de-duplicated on the way in). */
    public static SortedIdSet of(Collection<Long> values) {
        if (values.isEmpty()) {
            return EMPTY;
        }
        long[] a = new long[values.size()];
        int i = 0;
        for (long v : values) {
            a[i++] = v;
        }
        Arrays.sort(a);
        int n = 0;
        for (int k = 0; k < a.length; k++) {
            if (k == 0 || a[k] != a[k - 1]) {
                a[n++] = a[k];
            }
        }
        return new SortedIdSet(n == a.length ? a : Arrays.copyOf(a, n));
    }

    public int size() {
        return ids.length;
    }

    public boolean isEmpty() {
        return ids.length == 0;
    }

    public boolean contains(long value) {
        return Arrays.binarySearch(ids, value) >= 0;
    }

    /** True iff the two sets share at least one id. */
    public boolean intersects(SortedIdSet other) {
        if (this.ids.length == 0 || other.ids.length == 0) {
            return false;
        }
        // Iterate the smaller set, gallop through the larger — minimises probes.
        SortedIdSet small = this.ids.length <= other.ids.length ? this : other;
        SortedIdSet large = (small == this) ? other : this;

        int from = 0;
        for (long value : small.ids) {
            from = gallopToFirstGreaterOrEqual(large.ids, value, from);
            if (from == large.ids.length) {
                return false; // nothing left in the larger set can match
            }
            if (large.ids[from] == value) {
                return true;
            }
            // from now points past `value`; the next (larger) probe resumes here.
        }
        return false;
    }

    public long[] toArray() {
        return Arrays.copyOf(ids, ids.length);
    }

    /**
     * Index of the first element {@code >= value} at or after {@code from}, or
     * {@code a.length} if none. Exponential (galloping) expansion locates a window,
     * then a binary search pins the boundary inside it.
     */
    private static int gallopToFirstGreaterOrEqual(long[] a, long value, int from) {
        int n = a.length;
        if (from >= n) {
            return n;
        }
        if (a[from] >= value) {
            return from;
        }
        // a[from] < value: expand a window [prev, hi] doubling the stride each step,
        // maintaining the invariant a[prev] < value.
        int prev = from;
        int step = 1;
        int probe = from + step;
        while (probe < n && a[probe] < value) {
            prev = probe;
            step <<= 1;
            probe = from + step;
        }
        int hi = Math.min(probe, n - 1);

        int lo = prev + 1;
        int result = n;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] >= value) {
                result = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return result;
    }
}
