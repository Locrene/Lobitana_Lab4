package edu.cit.lobitana.inventory;

import java.util.Map;

/**
 * Outcome of an all-or-nothing reservation attempt.
 *
 * @param reserved  true when every requested line was reserved
 * @param shortages sku -> how many units were missing (empty when reserved)
 */
public record ReservationResult(boolean reserved, Map<String, Integer> shortages) {

    public static ReservationResult success() {
        return new ReservationResult(true, Map.of());
    }

    public static ReservationResult shortOf(Map<String, Integer> shortages) {
        return new ReservationResult(false, Map.copyOf(shortages));
    }
}
