package edu.cit.lobitana.channel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import edu.cit.lobitana.order.OrderLineRequest;
import edu.cit.lobitana.order.OrderService;
import edu.cit.lobitana.order.PlacementResult;

/**
 * Every write the channel makes to its own tables, in real transactions.
 *
 * It is a separate bean on purpose: the poller calls these methods through the Spring proxy, so each unit
 * of work either commits completely or not at all. The important one is
 * {@link #placeAndRecord(FeedEvent, List)} - creating the shop order, claiming the Tiangge order id and
 * (when the answer is already known) recording the event and the cursor happen in one commit, which is
 * what makes "exactly one shop order per Tiangge order" true even if the process is killed mid-batch.
 */
@Component
class ChannelBookkeeper {

    private static final Logger log = LoggerFactory.getLogger(ChannelBookkeeper.class);

    private final ChannelOrderRepository channelOrders;
    private final ProcessedFeedEventRepository processedEvents;
    private final FeedCursorRepository cursors;
    private final OrderService orders;

    ChannelBookkeeper(ChannelOrderRepository channelOrders,
                      ProcessedFeedEventRepository processedEvents,
                      FeedCursorRepository cursors,
                      OrderService orders) {
        this.channelOrders = channelOrders;
        this.processedEvents = processedEvents;
        this.cursors = cursors;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    long currentCursor() {
        return cursors.findById(FeedCursorState.SINGLETON_ID).map(FeedCursorState::getCursor).orElse(0L);
    }

    /** Move the cursor past an event we have already handled in an earlier run. */
    @Transactional
    void advanceCursor(long seq) {
        FeedCursorState cursor = cursors.findById(FeedCursorState.SINGLETON_ID)
                .orElseGet(() -> new FeedCursorState(0L));
        cursor.advanceTo(seq);
        cursors.save(cursor);
    }

    @Transactional(readOnly = true)
    boolean alreadyProcessed(String eventId) {
        return processedEvents.existsById(eventId);
    }

    @Transactional(readOnly = true)
    Optional<ChannelOrder> findOrder(String tianggeOrderId) {
        return channelOrders.findById(tianggeOrderId);
    }

    Optional<ChannelOrder> findByShopOrder(long shopOrderId) {
        return channelOrders.findByShopOrderId(shopOrderId);
    }

    /**
     * Take the order into the shop. One commit covers: claiming this Tiangge order id, creating the shop
     * order through the ordinary Order module, and - when every line was reserved - the decision, the
     * processed-event marker and the new cursor.
     *
     * When stock is short the decision is deliberately left open, because deciding between BACKORDERED and
     * REJECTED needs a question answered by LegacySupply, which must not happen inside a transaction.
     */
    @Transactional
    ChannelOrder placeAndRecord(FeedEvent event, List<OrderLineRequest> lines) {
        ChannelOrder link = channelOrders.save(
                new ChannelOrder(event.orderId(), event.placedAt(), event.decisionDeadline()));
        PlacementResult placement = orders.place(lines, "Tiangge order " + event.orderId());
        if (placement.reserved()) {
            link.decide(placement.orderId(), "ACCEPTED", null);
            channelOrders.save(link);
            markProcessed(event);
            return link;
        }
        link.claimShopOrder(placement.orderId(), asCsv(placement.shortages()));
        channelOrders.save(link);
        log.info("Tiangge order {} became shop order {} and is short of {}",
                event.orderId(), placement.orderId(), placement.shortages());
        return link;
    }

    /**
     * Write the decision for an order that was short, and - when this is driven by a feed event - record
     * the event and move the cursor in the same commit.
     */
    @Transactional
    void finaliseDecision(String tianggeOrderId, String decision, FeedEvent event) {
        channelOrders.findById(tianggeOrderId).ifPresent(link -> {
            if (link.getDecision() != null) {
                // Already answered - never overwrite a decision Tiangge may already have.
                if (event != null) {
                    markProcessed(event);
                }
                return;
            }
            Long shopOrderId = link.getShopOrderId();
            link.decide(shopOrderId == null ? 0L : shopOrderId, decision, link.getShortSkus());
            channelOrders.save(link);
            if (shopOrderId != null) {
                if ("BACKORDERED".equals(decision)) {
                    orders.markBackordered(shopOrderId);
                } else if ("REJECTED".equals(decision)) {
                    orders.markRejected(shopOrderId);
                }
            }
            if (event != null) {
                markProcessed(event);
            }
            log.info("Tiangge order {} decided {}", tianggeOrderId, decision);
        });
    }

    /** An order naming a sellerSku we do not list: answer REJECTED without creating a shop order. */
    @Transactional
    void rejectUnlistable(FeedEvent event) {
        ChannelOrder link = channelOrders.findById(event.orderId())
                .orElseGet(() -> new ChannelOrder(event.orderId(), event.placedAt(), event.decisionDeadline()));
        link.decide(0L, "REJECTED", null);
        channelOrders.save(link);
        markProcessed(event);
        log.warn("Tiangge order {} names a sellerSku this shop does not list, rejected", event.orderId());
    }

    /**
     * A customer cancelled on the marketplace: run it through the ordinary cancellation logic, which
     * restocks inventory and publishes the stock event, then remember that the confirmation is owed.
     */
    @Transactional
    void recordCancellation(FeedEvent event) {
        ChannelOrder link = channelOrders.findById(event.orderId()).orElseThrow(() -> new IllegalStateException(
                "cancellation for Tiangge order " + event.orderId() + " arrived before the order itself"));
        if (link.getShopOrderId() != null && link.getShopOrderId() > 0) {
            orders.cancel(link.getShopOrderId(), "cancelled on Tiangge");
        }
        link.cancellationRequested(event.cancelledAt(), event.confirmDeadline());
        channelOrders.save(link);
        markProcessed(event);
    }

    /** Nothing to do for this event beyond remembering that it is done. */
    @Transactional
    void acknowledge(FeedEvent event) {
        markProcessed(event);
    }

    @Transactional
    void markDecisionSent(String tianggeOrderId) {
        channelOrders.findById(tianggeOrderId).ifPresent(link -> {
            link.decisionDelivered();
            channelOrders.save(link);
        });
    }

    @Transactional
    void noteDecisionAttempt(String tianggeOrderId) {
        channelOrders.findById(tianggeOrderId).ifPresent(link -> {
            link.decisionAttempted();
            channelOrders.save(link);
        });
    }

    @Transactional
    void markCancellationConfirmed(String tianggeOrderId) {
        channelOrders.findById(tianggeOrderId).ifPresent(link -> {
            link.cancellationDelivered();
            channelOrders.save(link);
        });
    }

    @Transactional
    void markResolutionSent(String tianggeOrderId) {
        channelOrders.findById(tianggeOrderId).ifPresent(link -> {
            link.resolutionDelivered();
            channelOrders.save(link);
        });
    }

    /** A backorder reached its final answer in the shop; the marketplace still has to be told. */
    @Transactional
    Optional<ChannelOrder> recordResolution(long shopOrderId, String status) {
        Optional<ChannelOrder> link = channelOrders.findByShopOrderId(shopOrderId);
        link.ifPresent(found -> {
            found.resolveAs(status);
            channelOrders.save(found);
        });
        return link;
    }

    private void markProcessed(FeedEvent event) {
        if (!processedEvents.existsById(event.eventId())) {
            processedEvents.save(new ProcessedFeedEvent(event.eventId(), event.seq(), event.type(), event.orderId()));
        }
        FeedCursorState cursor = cursors.findById(FeedCursorState.SINGLETON_ID)
                .orElseGet(() -> new FeedCursorState(0L));
        cursor.advanceTo(event.seq());
        cursors.save(cursor);
    }

    private static String asCsv(Map<String, Integer> shortages) {
        return shortages.isEmpty() ? null : String.join(",", shortages.keySet());
    }
}
