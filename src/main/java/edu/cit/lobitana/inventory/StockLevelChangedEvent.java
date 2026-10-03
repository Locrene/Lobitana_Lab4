package edu.cit.lobitana.inventory;

/**
 * Lab 2 domain event: the available quantity of one SKU changed, for whatever reason.
 *
 * Inventory does not know who listens. A sales channel can listen to publish the new number, and the
 * supplier adapter listens to decide whether to buy more. That is why no timer is needed anywhere.
 */
public record StockLevelChangedEvent(String sku, int available, int onHand, int reserved, String reason) {
}
