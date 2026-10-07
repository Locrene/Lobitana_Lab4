package edu.cit.lobitana.supply;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import edu.cit.lobitana.common.StockAnnouncement;

/**
 * Lab 3 delivery tracking. LegacySupply never calls us, so every open purchase order is polled until it
 * reaches status 40 (Delivered); then {@link DeliveryBookkeeper} books the units into inventory once.
 *
 * Booking a delivery publishes a stock event, which is what eventually lets a backorder become ACCEPTED
 * and what sends the new number to the marketplace - this class knows about neither.
 *
 * Time matters here: once LegacySupply has answered "delivered", the new stock is expected on the
 * marketplace within 30 seconds, whether or not that answer actually reached us. So a status request that
 * failed or timed out is asked again within a few seconds instead of waiting for the next round, and a
 * booking that fails for a passing reason is retried on the spot.
 */
@Component
class DeliveryTracker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTracker.class);

    private static final int DELIVERED = 40;
    private static final int RATE_LIMITED = 429;
    private static final int BOOKING_ATTEMPTS = 3;
    /** An answer this recent is trusted, which keeps a burst of orders from becoming a burst of requests. */
    private static final Duration FRESH_ENOUGH = Duration.ofSeconds(2);
    /** After a request got no answer at all, how long its answer is treated as possibly "delivered". */
    private static final Duration UNCLEAR_FOR = Duration.ofSeconds(12);
    private static final Duration LET_NUMBERS_LAND = Duration.ofSeconds(3);

    private final LegacySupplyClient client;
    private final SupplierPurchaseOrderRepository purchaseOrders;
    private final DeliveryBookkeeper bookkeeper;
    private final StockAnnouncement announcement;

    /** When each open purchase order was last asked about, so nobody asks LegacySupply twice in a row. */
    private final Map<String, Instant> lastAsked = new ConcurrentHashMap<>();
    /** Purchase orders whose last status request failed: the answer may exist, we just did not get it. */
    private final Set<String> askAgainSoon = ConcurrentHashMap.newKeySet();
    /** Shop SKUs with a status request on its way right now. */
    private final Set<String> beingAsked = ConcurrentHashMap.newKeySet();
    /** Shop SKUs whose last status request got no answer, and until when that leaves the answer open. */
    private final Map<String, Instant> unclearUntil = new ConcurrentHashMap<>();
    /** One check at a time, so two threads can never book the same delivery twice. */
    private final ReentrantLock checking = new ReentrantLock();

    DeliveryTracker(LegacySupplyClient client,
                    SupplierPurchaseOrderRepository purchaseOrders,
                    DeliveryBookkeeper bookkeeper,
                    StockAnnouncement announcement) {
        this.announcement = announcement;
        this.client = client;
        this.purchaseOrders = purchaseOrders;
        this.bookkeeper = bookkeeper;
    }

    @Scheduled(initialDelay = 10_000, fixedDelayString = "${legacysupply.delivery-poll-ms:15000}")
    void pollOpenOrders() {
        purchaseOrders.findByReceivedAtIsNull().forEach(this::checkPatiently);
    }

    /** Only has work to do after a status request failed; otherwise it sends nothing. */
    @Scheduled(initialDelay = 12_000, fixedDelay = 3_000)
    void askAgainAfterAFailure() {
        for (String poNumber : List.copyOf(askAgainSoon)) {
            askAgainSoon.remove(poNumber);
            purchaseOrders.findById(poNumber)
                    .filter(SupplierPurchaseOrder::open)
                    .ifPresent(this::checkPatiently);
        }
    }

    /**
     * Ask about one purchase order right now, unless it was asked about a moment ago. Used before promising
     * a customer that stock is "on its way": if it has in fact already arrived, it is booked in on the spot.
     * It queues behind at most one other check, because an order is waiting for the answer.
     *
     * @return true when the order is no longer on its way: delivered and booked in, or cancelled
     */
    boolean arrivedMeanwhile(SupplierPurchaseOrder order) {
        Instant asked = lastAsked.get(order.getPoNumber());
        if (asked != null && asked.isAfter(Instant.now().minus(FRESH_ENOUGH))) {
            return false;
        }
        try {
            if (!checking.tryLock(9, TimeUnit.SECONDS)) {
                return false;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            return check(order);
        } catch (RuntimeException ex) {
            noteFailure(order, ex);
            return false;
        } finally {
            checking.unlock();
        }
    }

    /**
     * True while LegacySupply may already have answered "delivered" for this SKU without the units being
     * booked here yet: a status request is on its way, or the last one got no answer. Whoever announces
     * stock numbers waits for this to clear, so a number without the delivery never follows the delivery.
     */
    boolean answerPending(String sku) {
        if (beingAsked.contains(sku)) {
            return true;
        }
        Instant until = unclearUntil.get(sku);
        return until != null && until.isAfter(Instant.now());
    }

    private void checkPatiently(SupplierPurchaseOrder order) {
        checking.lock();
        try {
            check(order);
        } catch (RuntimeException ex) {
            // A slow or failing supplier is expected; this order is asked about again in a few seconds.
            noteFailure(order, ex);
        } finally {
            checking.unlock();
        }
    }

    private void noteFailure(SupplierPurchaseOrder order, RuntimeException ex) {
        String poNumber = order.getPoNumber();
        if (ex instanceof LegacySupplyException lse && lse.httpStatus() == 0) {
            // No answer at all: LegacySupply may have answered and the answer was lost on the way.
            unclearUntil.put(order.getSku(), Instant.now().plus(UNCLEAR_FOR));
        }
        log.warn("could not check purchase order {}: {}", poNumber, ex.getMessage());
        // Counted from the end of the attempt, so a hanging supplier is not asked again by every order.
        lastAsked.put(poNumber, Instant.now());
        boolean quotaUsedUp = ex instanceof LegacySupplyException lse && lse.httpStatus() == RATE_LIMITED;
        if (!quotaUsedUp) {
            askAgainSoon.add(poNumber);
        }
    }

    /** Callers hold {@link #checking}. */
    private boolean check(SupplierPurchaseOrder order) {
        beingAsked.add(order.getSku());
        try {
            // A stock number that is already travelling arrives before the supplier is asked.
            announcement.awaitNone(LET_NUMBERS_LAND);
            boolean closed = askAndBook(order);
            unclearUntil.remove(order.getSku());
            return closed;
        } finally {
            beingAsked.remove(order.getSku());
        }
    }

    private boolean askAndBook(SupplierPurchaseOrder order) {
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
            bookDelivery(poNumber, status.statusCode());
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

    /**
     * The supplier has said "delivered", so the clock is running. Booking can lose a race for an inventory
     * row with an order being placed at the same moment; that passes in milliseconds, so try again here
     * rather than a whole round later. Booking is once-only, so repeating it is safe.
     */
    private void bookDelivery(String poNumber, int statusCode) {
        for (int attempt = 1; ; attempt++) {
            try {
                bookkeeper.bookDelivery(poNumber, statusCode);
                return;
            } catch (RuntimeException ex) {
                if (attempt == BOOKING_ATTEMPTS) {
                    throw ex;
                }
                log.warn("booking the delivery of {} failed on attempt {}, trying again: {}",
                        poNumber, attempt, ex.getMessage());
                pause(200L * attempt);
            }
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
