package edu.cit.lobitana.order;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.cit.lobitana.inventory.InventoryService;
import edu.cit.lobitana.inventory.ReservationResult;

/**
 * Order logic (Lab 2), shared by every caller.
 *
 * An order typed into the React UI and an order handed over by a sales channel take exactly this path:
 * create the order, try to reserve all lines at once, and end up RESERVED or short. Nothing here knows
 * where an order came from.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final InventoryService inventory;
    private final ApplicationEventPublisher events;

    OrderService(OrderRepository orders, InventoryService inventory, ApplicationEventPublisher events) {
        this.orders = orders;
        this.inventory = inventory;
        this.events = events;
    }

    /**
     * Create an order and try to reserve it. The order row always survives, so the caller can refer to it
     * by id even when it could not be filled.
     */
    @Transactional
    public PlacementResult place(List<OrderLineRequest> requestedLines, String reason) {
        if (requestedLines == null || requestedLines.isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line");
        }
        List<OrderLine> lines = new ArrayList<>();
        requestedLines.forEach(line -> lines.add(new OrderLine(line.sku(), line.qty())));

        CustomerOrder order = orders.save(new CustomerOrder(lines));
        ReservationResult result = inventory.reserveAll(order.quantitiesBySku(), "order " + order.getId() + " (" + reason + ")");
        if (result.reserved()) {
            order.moveTo(OrderStatus.RESERVED);
            orders.save(order);
            log.info("order {} accepted ({} lines, {})", order.getId(), lines.size(), reason);
            return new PlacementResult(order.getId(), true, Map.of());
        }
        log.info("order {} cannot be filled now, short of {} ({})", order.getId(), result.shortages(), reason);
        return new PlacementResult(order.getId(), false, result.shortages());
    }

    @Transactional
    public void markRejected(long orderId) {
        orders.findById(orderId).ifPresent(order -> {
            if (order.getStatus() == OrderStatus.NEW) {
                order.moveTo(OrderStatus.REJECTED);
                orders.save(order);
                log.info("order {} rejected", orderId);
            }
        });
    }

    @Transactional
    public void markBackordered(long orderId) {
        orders.findById(orderId).ifPresent(order -> {
            if (order.getStatus() == OrderStatus.NEW) {
                order.moveTo(OrderStatus.BACKORDERED);
                orders.save(order);
                log.info("order {} backordered, waiting for stock", orderId);
            }
        });
    }

    /**
     * Cancel an order and release whatever it was holding. Idempotent: calling it again on an already
     * cancelled order changes nothing and reports false, so a redelivered cancellation cannot double-restock.
     */
    @Transactional
    public boolean cancel(long orderId, String reason) {
        Optional<CustomerOrder> found = orders.findById(orderId);
        if (found.isEmpty()) {
            log.warn("cancellation for unknown order {}", orderId);
            return false;
        }
        CustomerOrder order = found.get();
        if (order.getStatus() == OrderStatus.CANCELLED) {
            log.debug("order {} already cancelled, nothing to restock", orderId);
            return false;
        }
        boolean wasHoldingStock = order.getStatus() == OrderStatus.RESERVED;
        OrderStatus previous = order.getStatus();
        order.moveTo(OrderStatus.CANCELLED);
        orders.save(order);
        if (wasHoldingStock) {
            inventory.releaseAll(order.quantitiesBySku(), "cancellation of order " + orderId);
        }
        events.publishEvent(new OrderCancelledEvent(orderId, reason));
        log.info("order {} cancelled (was {}), restocked={}", orderId, previous, wasHoldingStock);
        return true;
    }

    /** Try again to reserve a backordered order, now that stock may have arrived. */
    @Transactional
    public BackorderOutcome tryFulfilBackorder(long orderId) {
        Optional<CustomerOrder> found = orders.findById(orderId);
        if (found.isEmpty() || found.get().getStatus() != OrderStatus.BACKORDERED) {
            return BackorderOutcome.stillShort(Map.of());
        }
        CustomerOrder order = found.get();
        ReservationResult result = inventory.reserveAll(order.quantitiesBySku(), "backorder fill for order " + orderId);
        if (!result.reserved()) {
            log.debug("backorder {} still short of {}", orderId, result.shortages());
            return BackorderOutcome.stillShort(result.shortages());
        }
        order.moveTo(OrderStatus.RESERVED);
        orders.save(order);
        events.publishEvent(new BackorderResolvedEvent(orderId, true));
        log.info("backorder {} filled from a delivery", orderId);
        return BackorderOutcome.success();
    }

    /** Give up on a backorder: it cannot be filled and nothing more is coming. */
    @Transactional
    public void giveUpOnBackorder(long orderId) {
        orders.findById(orderId).ifPresent(order -> {
            if (order.getStatus() != OrderStatus.BACKORDERED) {
                return;
            }
            order.moveTo(OrderStatus.CANCELLED);
            orders.save(order);
            events.publishEvent(new BackorderResolvedEvent(orderId, false));
            log.info("backorder {} cancelled, cannot be filled", orderId);
        });
    }

    @Transactional(readOnly = true)
    public List<CustomerOrder> backorderedWaitingFor(String sku) {
        return orders.findBackorderedWaitingFor(sku);
    }

    /** Every order still waiting for stock, oldest first. */
    @Transactional(readOnly = true)
    public List<CustomerOrder> backordered() {
        return orders.findByStatusOrderByIdAsc(OrderStatus.BACKORDERED);
    }

    @Transactional(readOnly = true)
    public Optional<CustomerOrder> find(long orderId) {
        return orders.findById(orderId);
    }

    @Transactional(readOnly = true)
    public List<CustomerOrder> recent() {
        return orders.findAll();
    }
}
