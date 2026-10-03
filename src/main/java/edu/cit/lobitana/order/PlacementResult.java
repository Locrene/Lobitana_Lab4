package edu.cit.lobitana.order;

import java.util.Map;

/**
 * What happened when an order was placed.
 *
 * @param orderId   the order that now exists in this shop (it exists either way)
 * @param reserved  true when every line was reserved
 * @param shortages sku -> missing units when {@code reserved} is false
 */
public record PlacementResult(long orderId, boolean reserved, Map<String, Integer> shortages) {
}
