package edu.cit.lobitana.supply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import edu.cit.lobitana.inventory.InventoryService;

/**
 * The transactional half of delivery tracking, kept in its own bean so the transactions really apply
 * (a self-call inside {@link DeliveryTracker} would bypass the proxy).
 */
@Component
class DeliveryBookkeeper {

    private static final Logger log = LoggerFactory.getLogger(DeliveryBookkeeper.class);

    private final SupplierPurchaseOrderRepository purchaseOrders;
    private final InventoryService inventory;
    private final ApplicationEventPublisher events;

    DeliveryBookkeeper(SupplierPurchaseOrderRepository purchaseOrders,
                       InventoryService inventory,
                       ApplicationEventPublisher events) {
        this.purchaseOrders = purchaseOrders;
        this.inventory = inventory;
        this.events = events;
    }

    @Transactional
    void noteProgress(String poNumber, int statusCode) {
        purchaseOrders.findById(poNumber).ifPresent(order -> {
            order.updateStatus(statusCode);
            purchaseOrders.save(order);
            log.info("purchase order {} is now status {}", poNumber, statusCode);
        });
    }

    /**
     * The supplier ended this order without delivering it. It stops counting as "on its way", nothing is
     * added to inventory, and the next low-stock check or waiting backorder places a fresh order.
     */
    @Transactional
    void closeWithoutDelivery(String poNumber, int statusCode) {
        purchaseOrders.findById(poNumber).ifPresent(order -> {
            if (!order.open()) {
                return;
            }
            order.updateStatus(statusCode);
            order.markClosed();
            purchaseOrders.save(order);
            log.warn("purchase order {} for {} ended with status {} and will not arrive; no stock booked",
                    poNumber, order.getSku(), statusCode);
        });
    }

    /**
     * Receive a delivery once. The {@code receivedAt} guard inside the transaction means a second sighting
     * of status 40 cannot inflate stock.
     */
    @Transactional
    void bookDelivery(String poNumber, int statusCode) {
        purchaseOrders.findById(poNumber).ifPresent(order -> {
            if (!order.open()) {
                return;
            }
            order.updateStatus(statusCode);
            order.markClosed();
            purchaseOrders.save(order);
            int units = order.unitsOrdered();
            inventory.receiveStock(order.getSku(), units, "delivery of purchase order " + poNumber);
            events.publishEvent(new PurchaseOrderDeliveredEvent(poNumber, order.getSku(), order.getSupplierSku(), units));
            log.info("purchase order {} delivered: {} units of {} booked in", poNumber, units, order.getSku());
        });
    }
}
