package dev.zanzibar.model;

/**
 * An opaque consistency token wrapping a monotonic revision number.
 * Used to specify freshness bounds on permission checks.
 *
 * In production Zanzibar, this encodes a Spanner timestamp.
 * Here, it wraps a simple monotonic long.
 */
public record Zookie(long revision) {

    public static final Zookie ANY = new Zookie(Long.MAX_VALUE);

    public byte[] encode() {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (revision >>> (8 * (7 - i)));
        }
        return bytes;
    }

    public static Zookie decode(byte[] bytes) {
        if (bytes.length != 8) {
            throw new IllegalArgumentException("Zookie must be 8 bytes");
        }
        long rev = 0;
        for (int i = 0; i < 8; i++) {
            rev = (rev << 8) | (bytes[i] & 0xFF);
        }
        return new Zookie(rev);
    }

    @Override
    public String toString() {
        return "zookie(" + revision + ")";
    }
}
