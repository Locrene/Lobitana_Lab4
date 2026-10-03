package edu.cit.lobitana.channel;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import edu.cit.lobitana.order.OrderLineRequest;

/**
 * What this shop does with one feed event (Task 4 and Task 5).
 *
 * The order of the steps is the whole point:
 * <ol>
 *   <li>an event we have already handled only moves the cursor - nothing is processed twice;</li>
 *   <li>an order we already know about does not become a second shop order, it just gets its decision
 *       delivered again, which is how a redelivered order is handled;</li>
 *   <li>a new order is translated into shop SKUs and goes through the ordinary Order module;</li>
 *   <li>if stock is short, the supplier is asked whether anything is on the way, and only then is the
 *       answer BACKORDERED or REJECTED;</li>
 *   <li>a cancellation runs through the ordinary cancellation logic, which restocks inventory.</li>
 * </ol>
 */
@Component
class FeedEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(FeedEventProcessor.class);

    private static final Duration STOCK_PATIENCE = Duration.ofSeconds(2);

    private final ChannelBookkeeper bookkeeper;
    private final OrderTranslator translator;
    private final ShortageDecider shortageDecider;
    private final DecisionOutbox outbox;
    private final StockSyncListener stockSync;

    FeedEventProcessor(ChannelBookkeeper bookkeeper,
                       OrderTranslator translator,
                       ShortageDecider shortageDecider,
                       DecisionOutbox outbox,
                       StockSyncListener stockSync) {
        this.stockSync = stockSync;
        this.bookkeeper = bookkeeper;
        this.translator = translator;
        this.shortageDecider = shortageDecider;
        this.outbox = outbox;
    }

    void process(FeedEvent event) {
        if (event.eventId() == null || event.orderId() == null) {
            log.warn("feed event at seq {} has no id, skipping", event.seq());
            bookkeeper.advanceCursor(event.seq());
            return;
        }
        if (bookkeeper.alreadyProcessed(event.eventId())) {
            log.debug("event {} (seq {}) was already handled", event.eventId(), event.seq());
            bookkeeper.advanceCursor(event.seq());
            return;
        }
        if (event.placed()) {
            handleOrderPlaced(event);
        } else if (event.cancelled()) {
            handleOrderCancelled(event);
        } else {
            log.warn("feed event {} has unknown type {}", event.eventId(), event.type());
            bookkeeper.acknowledge(event);
        }
    }

    private void handleOrderPlaced(FeedEvent event) {
        Optional<ChannelOrder> known = bookkeeper.findOrder(event.orderId());
        if (known.isPresent()) {
            ChannelOrder link = known.get();
            if (link.getDecision() == null && link.getShopOrderId() != null) {
                // A previous run placed the shop order but never finished the answer.
                String decision = shortageDecider.decide(link.getTianggeOrderId(), link.getShortSkus());
                bookkeeper.finaliseDecision(link.getTianggeOrderId(), decision, event);
            } else {
                log.info("Tiangge order {} was delivered again, keeping the one shop order we already made",
                        event.orderId());
                bookkeeper.acknowledge(event);
            }
            outbox.flush(event.orderId());
            return;
        }

        Optional<List<OrderLineRequest>> shopLines = translator.toShopLines(event);
        if (shopLines.isEmpty()) {
            bookkeeper.rejectUnlistable(event);
            outbox.flush(event.orderId());
            return;
        }

        // Whatever came in a moment ago (a delivery, a cancellation) is announced before it can be sold.
        stockSync.awaitRisesPublished(STOCK_PATIENCE);
        ChannelOrder link = bookkeeper.placeAndRecord(event, shopLines.get());
        if (link.getDecision() == null) {
            String decision = shortageDecider.decide(event.orderId(), link.getShortSkus());
            if ("REJECTED".equals(decision)) {
                // The marketplace should already show the shortage this refusal is based on.
                stockSync.awaitPublished(STOCK_PATIENCE);
            }
            bookkeeper.finaliseDecision(event.orderId(), decision, event);
        }
        outbox.flush(event.orderId());
    }

    private void handleOrderCancelled(FeedEvent event) {
        bookkeeper.recordCancellation(event);
        outbox.flush(event.orderId());
        // The units are back. Let the marketplace hear that before the next order may take them.
        stockSync.awaitRisesPublished(STOCK_PATIENCE);
    }
}
