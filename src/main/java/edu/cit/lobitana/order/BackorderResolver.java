package edu.cit.lobitana.order;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import edu.cit.lobitana.supply.PurchaseOrderDeliveredEvent;
import edu.cit.lobitana.supply.SupplierPort;

/**
 * Closes the loop for orders that were waiting on stock.
 *
 * It works in two steps. While the delivery is still being booked - inside that same transaction, so no new
 * order can take the units first - every backorder waiting for the SKU is retried, oldest first. After the
 * booking has committed, whatever is still short only keeps waiting while another purchase order is
 * genuinely on its way; otherwise it is cancelled. Whoever took the order learns the answer through
 * {@link BackorderResolvedEvent}.
 */
@Component
class BackorderResolver {

    private static final Logger log = LoggerFactory.getLogger(BackorderResolver.class);

    private final OrderService orders;
    private final SupplierPort supplier;

    BackorderResolver(OrderService orders, SupplierPort supplier) {
        this.orders = orders;
        this.supplier = supplier;
    }

    /** Step one, in the delivery's own transaction: the waiting orders get the new units before anyone else. */
    @EventListener
    void onDelivery(PurchaseOrderDeliveredEvent event) {
        List<CustomerOrder> waiting = orders.backorderedWaitingFor(event.sku());
        if (waiting.isEmpty()) {
            return;
        }
        log.info("delivery of {} units of {} arrived, retrying {} backorder(s)",
                event.unitsReceived(), event.sku(), waiting.size());
        waiting.forEach(order -> orders.tryFulfilBackorder(order.getId()));
    }

    /** Step two, once the delivery is committed: decide what happens to the orders it could not fill. */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void afterDelivery(PurchaseOrderDeliveredEvent event) {
        orders.backorderedWaitingFor(event.sku()).forEach(this::settle);
    }

    /**
     * Safety net: a backorder must not wait forever. This catches one whose follow-up was lost to a restart,
     * and one that can be filled from stock a cancellation gave back.
     */
    @Scheduled(initialDelay = 40_000, fixedDelay = 15_000)
    void sweepWaitingOrders() {
        orders.backordered().forEach(this::settle);
    }

    private void settle(CustomerOrder order) {
        long orderId = order.getId();
        try {
            BackorderOutcome outcome = orders.tryFulfilBackorder(orderId);
            if (outcome.filled() || outcome.shortages().isEmpty()) {
                // Filled, or no longer waiting at all (cancelled or filled by someone else meanwhile).
                return;
            }
            boolean moreComing = outcome.shortages().keySet().stream().allMatch(sku ->
                    supplier.ensureStockOnTheWay(sku, "backorder " + orderId + " is still waiting").isPresent());
            if (moreComing) {
                log.debug("backorder {} keeps waiting, a delivery is on its way for {}",
                        orderId, outcome.shortages().keySet());
            } else {
                orders.giveUpOnBackorder(orderId);
            }
        } catch (RuntimeException ex) {
            // One order failing must not stop the others; the sweep comes back to it.
            log.warn("could not settle backorder {} this time: {}", orderId, ex.getMessage());
        }
    }
}
