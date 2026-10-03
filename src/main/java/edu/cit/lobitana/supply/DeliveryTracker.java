package edu.cit.lobitana.supply;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lab 3 delivery tracking. LegacySupply never calls us, so every open purchase order is polled until it
 * reaches status 40 (Delivered); then {@link DeliveryBookkeeper} books the units into inventory once.
 *
 * Booking a delivery publishes a stock event, which is what eventually lets a backorder become ACCEPTED
 * and what sends the new number to the marketplace - this class knows about neither.
 */
@Component
class DeliveryTracker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTracker.class);

    private static final int DELIVERED = 40;
    /** An answer this recent is trusted, which keeps a burst of orders from becoming a burst of requests. */
    private static final Duration FRESH_ENOUGH = Duration.ofSeconds(2);

    private final LegacySupplyClient client;
    private final SupplierPurchaseOrderRepository purchaseOrders;
    private final DeliveryBookkeeper bookkeeper;

    /** When each open purchase order was last asked about, so nobody asks LegacySupply twice in a row. */
    private final Map<String, Instant> lastAsked = new ConcurrentHashMap<>();

    DeliveryTracker(LegacySupplyClient client,
                    SupplierPurchaseOrderRepository purchaseOrders,
                    DeliveryBookkeeper bookkeeper) {
        this.client = client;
        this.purchaseOrders = purchaseOrders;
        this.bookkeeper = bookkeeper;
    }

    @Scheduled(initialDelay = 10_000, fixedDelayString = "${legacysupply.delivery-poll-ms:8000}")
    void pollOpenOrders() {
        List<SupplierPurchaseOrder> open = purchaseOrders.findByReceivedAtIsNull();
        if (open.isEmpty()) {
            return;
        }
        for (SupplierPurchaseOrder order : open) {
            try {
                check(order);
            } catch (RuntimeException ex) {
                // A slow or failing supplier is expected; the next sweep tries again.
                log.warn("could not check purchase order {}: {}", order.getPoNumber(), ex.getMessage());
            }
        }
    }

    /**
     * Ask about one purchase order right now, unless it was asked about a moment ago. Used before promising
     * a customer that stock is "on its way": if it has in fact already arrived, it is booked in on the spot.
     *
     * @return true when the order is no longer on its way: delivered and booked in, or cancelled
     */
    boolean arrivedMeanwhile(SupplierPurchaseOrder order) {
        Instant asked = lastAsked.get(order.getPoNumber());
        if (asked != null && asked.isAfter(Instant.now().minus(FRESH_ENOUGH))) {
            return false;
        }
        try {
            return check(order);
        } catch (RuntimeException ex) {
            log.warn("could not check purchase order {}: {}", order.getPoNumber(), ex.getMessage());
            return false;
        }
    }

    /** One at a time, so the sweep and an on-the-spot check can never book the same delivery twice. */
    private synchronized boolean check(SupplierPurchaseOrder order) {
        String poNumber = order.getPoNumber();
        lastAsked.put(poNumber, Instant.now());
        PurchaseOrderStatusResponse status;
        try {
            status = client.orderStatus(poNumber);
        } catch (LegacySupplyException ex) {
            if (ex.httpStatus() != 404) {
                throw ex;
            }
            // LegacySupply no longer knows this order (E-PO-04), so nothing will ever arrive for it.
            bookkeeper.closeWithoutDelivery(poNumber, ex.httpStatus());
            lastAsked.remove(poNumber);
            return true;
        }
        if (status.statusCode() == DELIVERED) {
            bookkeeper.bookDelivery(poNumber, status.statusCode());
            lastAsked.remove(poNumber);
            return true;
        }
        if (status.statusCode() > DELIVERED) {
            // Not in the manual, but seen on our own record: 90 is an order LegacySupply cancelled.
            // Anything past 40 is final and brings no goods, so it must never be booked as stock.
            bookkeeper.closeWithoutDelivery(poNumber, status.statusCode());
            lastAsked.remove(poNumber);
            return true;
        }
        if (status.statusCode() != order.getStatusCode()) {
            bookkeeper.noteProgress(poNumber, status.statusCode());
        }
        return false;
    }
}
