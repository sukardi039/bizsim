package com.bizsim.sim;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Seeded draws. Every "unpredictable" thing in the simulation — an R&D threshold, whether a
 * geopolitical event fires, the noise in a forecast — comes from here, keyed on something immutable.
 *
 * <p>That is what keeps decision 4 intact: the trainee cannot predict it, but the server computes
 * the same answer every time the same key is hashed, so a voided round, a retry or a dispute in
 * front of the class produces identical numbers.
 */
public final class Seed {

    private Seed() {
    }

    /** A uniform value in [0, 1) for this key. */
    public static BigDecimal uniform(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits = (bits << 8) | (bytes[i] & 0xffL);
            }
            double value = (double) ((bits & 0x7fffffffffffffffL) >>> 11) / (double) (1L << 53);
            return BigDecimal.valueOf(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
