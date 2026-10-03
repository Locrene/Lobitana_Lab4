package edu.cit.lobitana.channel;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Everything this shop owes the marketplace: decisions, cancellation confirmations and backorder
 * resolutions.
 *
 * The shop always makes up its mind locally first and commits that, then tells Tiangge. If Tiangge is slow
 * or down, the message simply stays owed and the sweep tries again - the manual says these three calls are
 * safe to retry with identical content, and the content is read from the committed row, so a retry is
 * always identical. That is also how a restart finishes work the previous process started.
 *
 * Telling happens on a few courier threads rather than on the feed thread, so one slow answer from the
 * marketplace does not hold up the decision for the next order in a flash sale.
 */
@Component
class DecisionOutbox {

    private static final Logger log = LoggerFactory.getLogger(DecisionOutbox.class);

    private final TianggeClient client;
    private final ChannelOrderRepository channelOrders;
    private final ChannelBookkeeper bookkeeper;
    private final ShortageDecider shortageDecider;
    private final HeartbeatPublisher heartbeat;

    private final AtomicInteger courierNumber = new AtomicInteger();
    private final ExecutorService couriers = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "outbox-" + courierNumber.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    /** Orders a courier is working on right now, so the sweep and the feed never send the same thing twice. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** Orders that got something new to send while a courier was already busy with them. */
    private final Set<String> askedAgain = ConcurrentHashMap.newKeySet();

    DecisionOutbox(TianggeClient client,
                   ChannelOrderRepository channelOrders,
                   ChannelBookkeeper bookkeeper,
                   ShortageDecider shortageDecider,
                   HeartbeatPublisher heartbeat) {
        this.client = client;
        this.channelOrders = channelOrders;
        this.bookkeeper = bookkeeper;
        this.shortageDecider = shortageDecider;
        this.heartbeat = heartbeat;
    }

    /** Push whatever is owed for one order right now; called the moment something is committed. */
    void flush(String tianggeOrderId) {
        if (!inFlight.add(tianggeOrderId)) {
            askedAgain.add(tianggeOrderId);
            return;
        }
        couriers.execute(() -> {
            try {
                askedAgain.remove(tianggeOrderId);
                channelOrders.findById(tianggeOrderId).ifPresent(this::deliver);
            } catch (RuntimeException ex) {
                log.warn("could not deliver for order {} yet: {}", tianggeOrderId, ex.getMessage());
            } finally {
                inFlight.remove(tianggeOrderId);
            }
            if (askedAgain.remove(tianggeOrderId)) {
                flush(tianggeOrderId);
            }
        });
    }

    /**
     * Catch up on anything that did not get through, including an order whose decision was never finished
     * because the process stopped halfway.
     */
    @Scheduled(initialDelay = 8_000, fixedDelay = 3_000)
    void sweep() {
        if (!heartbeat.online()) {
            return;
        }
        for (ChannelOrder stuck : channelOrders.findByDecisionIsNull()) {
            if (stuck.getShopOrderId() == null || !clearlyAbandoned(stuck)) {
                // Give the poller, which is already working on this order, the chance to finish first.
                continue;
            }
            String decision = shortageDecider.decide(stuck.getTianggeOrderId(), stuck.getShortSkus());
            bookkeeper.finaliseDecision(stuck.getTianggeOrderId(), decision, null);
        }
        Set<String> owing = new LinkedHashSet<>();
        channelOrders.findByDecisionIsNotNullAndDecisionSentFalse()
                .forEach(link -> owing.add(link.getTianggeOrderId()));
        channelOrders.findByCancellationRequestedTrueAndCancellationConfirmedFalse()
                .forEach(link -> owing.add(link.getTianggeOrderId()));
        channelOrders.findByResolutionIsNotNullAndResolutionSentFalse()
                .forEach(link -> owing.add(link.getTianggeOrderId()));
        owing.stream().filter(orderId -> !inFlight.contains(orderId)).forEach(this::flush);
    }

    @PreDestroy
    void stop() {
        couriers.shutdownNow();
    }

    /** True once an order has been sitting undecided so long that the poller is clearly not on it. */
    private boolean clearlyAbandoned(ChannelOrder link) {
        Instant createdAt = link.getCreatedAt();
        return createdAt == null || createdAt.isBefore(Instant.now().minus(Duration.ofSeconds(40)));
    }

    /**
     * In the order the marketplace needs them: it cannot confirm a cancellation or resolve a backorder for
     * an order whose decision it has not received, so a step that fails stops the ones behind it.
     */
    private void deliver(ChannelOrder link) {
        if (link.getDecision() == null) {
            return;
        }
        if (!link.isDecisionSent() && !deliverDecision(link)) {
            return;
        }
        if (link.isCancellationRequested() && !link.isCancellationConfirmed() && !deliverCancellation(link)) {
            return;
        }
        if (link.getResolution() != null && !link.isResolutionSent()) {
            deliverResolution(link);
        }
    }

    private boolean deliverDecision(ChannelOrder link) {
        String orderId = link.getTianggeOrderId();
        String shopOrderId = shopOrderIdFor(link);
        bookkeeper.noteDecisionAttempt(orderId);
        try {
            client.sendDecision(orderId, link.getDecision(), shopOrderId, reasonFor(link));
            bookkeeper.markDecisionSent(orderId);
            warnIfLate("decision", orderId, link.getDecisionDeadline());
            log.info("told Tiangge order {} is {} (shop order {})", orderId, link.getDecision(), shopOrderId);
            return true;
        } catch (TianggeApiException ex) {
            if (ex.settled()) {
                // Already decided on their side, or the order is gone: repeating cannot change it.
                bookkeeper.markDecisionSent(orderId);
                return true;
            }
            log.warn("could not report the decision for order {} yet: {}", orderId, ex.getMessage());
            return false;
        }
    }

    private boolean deliverCancellation(ChannelOrder link) {
        String orderId = link.getTianggeOrderId();
        try {
            client.confirmCancellation(orderId);
            bookkeeper.markCancellationConfirmed(orderId);
            warnIfLate("cancellation confirmation", orderId, link.getConfirmDeadline());
            log.info("confirmed the cancellation of Tiangge order {} and restocked", orderId);
            return true;
        } catch (TianggeApiException ex) {
            if (ex.settled()) {
                bookkeeper.markCancellationConfirmed(orderId);
                return true;
            }
            log.warn("could not confirm the cancellation of order {} yet: {}", orderId, ex.getMessage());
            return false;
        }
    }

    private void deliverResolution(ChannelOrder link) {
        String orderId = link.getTianggeOrderId();
        try {
            client.sendResolution(orderId, link.getResolution());
            bookkeeper.markResolutionSent(orderId);
            log.info("resolved backorder {} as {}", orderId, link.getResolution());
        } catch (TianggeApiException ex) {
            if (ex.settled()) {
                bookkeeper.markResolutionSent(orderId);
                return;
            }
            log.warn("could not resolve backorder {} yet: {}", orderId, ex.getMessage());
        }
    }

    /**
     * Tiangge wants a shopOrderId string. Normally it is our order id; the only exception is an order for a
     * sellerSku this shop does not list, where no shop order was created.
     */
    private String shopOrderIdFor(ChannelOrder link) {
        Long shopOrderId = link.getShopOrderId();
        if (shopOrderId == null || shopOrderId == 0L) {
            return "unlisted-" + link.getTianggeOrderId();
        }
        return String.valueOf(shopOrderId);
    }

    private String reasonFor(ChannelOrder link) {
        return switch (link.getDecision()) {
            case "ACCEPTED" -> "all lines reserved from inventory";
            case "BACKORDERED" -> "waiting for a LegacySupply delivery of " + link.getShortSkus();
            case "REJECTED" -> link.getShortSkus() == null
                    ? "not sellable by this shop"
                    : "out of stock and no restock on the way for " + link.getShortSkus();
            default -> null;
        };
    }

    private void warnIfLate(String what, String orderId, Instant deadline) {
        if (deadline != null && Instant.now().isAfter(deadline)) {
            log.warn("the {} for order {} went out after its deadline ({})", what, orderId, deadline);
        }
    }
}
