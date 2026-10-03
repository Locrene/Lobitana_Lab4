package edu.cit.lobitana.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import edu.cit.lobitana.order.BackorderResolvedEvent;

/**
 * Task 6, marketplace side: a backorder reached its final answer in the shop, so Tiangge is told.
 *
 * The shop decides this on its own - a LegacySupply delivery arrives, the Order module retries the waiting
 * order and publishes the outcome. This listener only translates that outcome into the marketplace word.
 * The resolution is written in the same transaction as the order's own change, so the two cannot disagree
 * after a crash, and it is handed to the outbox once that transaction has committed - which is what makes
 * the resolution survive a failing marketplace.
 */
@Component
class BackorderNotifier {

    private static final Logger log = LoggerFactory.getLogger(BackorderNotifier.class);

    private final ChannelBookkeeper bookkeeper;
    private final DecisionOutbox outbox;

    BackorderNotifier(ChannelBookkeeper bookkeeper, DecisionOutbox outbox) {
        this.bookkeeper = bookkeeper;
        this.outbox = outbox;
    }

    @EventListener
    void onBackorderResolved(BackorderResolvedEvent event) {
        String status = event.fulfilled() ? "ACCEPTED" : "CANCELLED";
        bookkeeper.recordResolution(event.orderId(), status).ifPresent(link ->
                log.info("shop order {} (Tiangge order {}) resolves to {}",
                        event.orderId(), link.getTianggeOrderId(), status));
    }

    @TransactionalEventListener(fallbackExecution = true)
    void afterBackorderResolved(BackorderResolvedEvent event) {
        bookkeeper.findByShopOrder(event.orderId()).ifPresent(link -> outbox.flush(link.getTianggeOrderId()));
    }
}
