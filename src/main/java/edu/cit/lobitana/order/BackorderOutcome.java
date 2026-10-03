package edu.cit.lobitana.order;

import java.util.Map;

/**
 * Result of retrying a backordered order after a delivery.
 *
 * @param filled    true when every line is now reserved
 * @param shortages sku -> units still missing when it could not be filled
 */
public record BackorderOutcome(boolean filled, Map<String, Integer> shortages) {

    static BackorderOutcome success() {
        return new BackorderOutcome(true, Map.of());
    }

    static BackorderOutcome stillShort(Map<String, Integer> shortages) {
        return new BackorderOutcome(false, Map.copyOf(shortages));
    }
}
