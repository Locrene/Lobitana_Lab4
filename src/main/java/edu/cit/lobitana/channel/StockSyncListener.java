package edu.cit.lobitana.channel;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import edu.cit.lobitana.inventory.InventoryService;
import edu.cit.lobitana.inventory.StockLevelChangedEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Task 3: when stock changes for any reason, the marketplace learns the new number.
 *
 * There is no timer here. Inventory publishes a domain event whenever available quantity moves - a
 * marketplace order, an order from the React UI, a cancellation, a supplier delivery - and after that
 * transaction commits the SKU is handed to one worker thread, which sends a PUT /stock.
 *
 * Three things keep the number on the marketplace right:
 * <ul>
 *   <li>one worker, so two updates for the same SKU can never overtake each other;</li>
 *   <li>the worker reads the available quantity at the moment it sends, so what goes out is the current
 *       truth rather than a figure that was true when the event was raised;</li>
 *   <li>a failed update is put back and tried again, so a marketplace outage delays the number instead of
 *       leaving a stale one behind.</li>
 * </ul>
 * The order of messages matters too. A number always follows every decision, cancellation confirmation
 * and backorder resolution the marketplace is still owed, as the manual asks - one delivery can fill
 * several backorders, and the marketplace must hear all of those answers before the number that already
 * has them subtracted. It never waits long, because the number is owed within 30 seconds. When stock went
 * up (a delivery, a cancellation) the feed waits a moment for the number to go out, so that no order is
 * accepted against units the marketplace has not heard about.
 */
@Component
class StockSyncListener {

    private static final Logger log = LoggerFactory.getLogger(StockSyncListener.class);

    private static final Duration LONGEST_WAIT_FOR_DECISIONS = Duration.ofSeconds(20);
    private static final long RETRY_PAUSE_MS = 2_000L;

    private final TianggeClient client;
    private final ListingRepository listings;
    private final ChannelOrderRepository channelOrders;
    private final InventoryService inventory;

    private final BlockingQueue<String> changed = new LinkedBlockingQueue<>();
    /** SKUs already waiting in the queue, so a burst of events for one SKU becomes one update. */
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    /** The number the marketplace currently has for each shop SKU. */
    private final Map<String, Integer> lastSent = new ConcurrentHashMap<>();
    /** SKUs that now have more than the marketplace has been told. */
    private final Set<String> risen = ConcurrentHashMap.newKeySet();
    /** Tiangge refuses stock for a sellerSku it has not been given as a listing yet. */
    private volatile boolean listingsAccepted;
    private volatile boolean sending;
    private Thread worker;

    StockSyncListener(TianggeClient client,
                      ListingRepository listings,
                      ChannelOrderRepository channelOrders,
                      InventoryService inventory) {
        this.client = client;
        this.listings = listings;
        this.channelOrders = channelOrders;
        this.inventory = inventory;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onStockChanged(StockLevelChangedEvent event) {
        if (!listingsAccepted) {
            return;
        }
        Integer told = lastSent.get(event.sku());
        if (told != null && event.available() > told) {
            risen.add(event.sku());
        }
        publishSoon(event.sku());
    }

    /** Called once the marketplace has the listings; every listed SKU then gets its opening number. */
    void listingsAccepted(List<String> skus) {
        listingsAccepted = true;
        skus.forEach(this::publishSoon);
    }

    /** Ask for the current number of this shop SKU to be sent. Returns at once. */
    void publishSoon(String sku) {
        if (queued.add(sku)) {
            changed.add(sku);
        }
    }

    /**
     * Wait a moment until the marketplace has heard about every unit that came in. Called before an order
     * is taken, so it is never accepted against stock Tiangge does not know this shop has.
     */
    void awaitRisesPublished(Duration longest) {
        await(longest, () -> !risen.isEmpty());
    }

    /**
     * Wait a moment until every number asked for so far has gone out. Called before an order is turned
     * down, so the marketplace already shows the shortage the refusal is based on.
     */
    void awaitPublished(Duration longest) {
        await(longest, () -> sending || !queued.isEmpty());
    }

    @PostConstruct
    void start() {
        worker = new Thread(this::work, "stock-sync");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        worker.interrupt();
    }

    private void work() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<String> skus = new ArrayList<>();
                skus.add(changed.take());
                changed.drainTo(skus);
                sending = true;
                // Every answer the marketplace is owed goes first, so a number never arrives ahead of
                // the accepted order (or filled backorder) that is already counted in it.
                waitWhile(this::answersAreOwed);
                // From here on a new change of these SKUs asks for a new update.
                skus.forEach(queued::remove);
                publish(skus);
            } catch (InterruptedException ex) {
                return;
            } catch (RuntimeException ex) {
                log.warn("stock sync hit a problem and carries on: {}", ex.getMessage());
            } finally {
                sending = false;
            }
        }
    }

    private void publish(List<String> skus) throws InterruptedException {
        List<String> listed = new ArrayList<>();
        List<StockPayload> numbers = new ArrayList<>();
        for (String sku : skus) {
            listings.findBySku(sku).ifPresentOrElse(listing -> {
                listed.add(sku);
                numbers.add(new StockPayload(listing.getSellerSku(), inventory.available(sku)));
            }, () -> risen.remove(sku));
        }
        if (numbers.isEmpty()) {
            return;
        }
        try {
            client.publishStock(numbers);
            for (int i = 0; i < listed.size(); i++) {
                lastSent.put(listed.get(i), numbers.get(i).available());
                risen.remove(listed.get(i));
                log.info("told Tiangge that {} is now {}", numbers.get(i).sellerSku(), numbers.get(i).available());
            }
        } catch (TianggeApiException ex) {
            if (!ex.retryable()) {
                listed.forEach(risen::remove);
                log.error("Tiangge refused the stock update for {}: {}", listed, ex.getMessage());
                return;
            }
            log.warn("could not publish stock for {} yet, trying again: {}", listed, ex.getMessage());
            Thread.sleep(RETRY_PAUSE_MS);
            listed.forEach(this::publishSoon);
        }
    }

    /** Stock goes out after the answers that caused it - within reason. */
    private void waitWhile(Condition somethingGoesFirst) throws InterruptedException {
        Instant giveUpAt = Instant.now().plus(LONGEST_WAIT_FOR_DECISIONS);
        while (somethingGoesFirst.holds() && Instant.now().isBefore(giveUpAt)) {
            Thread.sleep(100);
        }
    }

    private boolean answersAreOwed() {
        return channelOrders.existsByDecisionIsNotNullAndDecisionSentFalse()
                || channelOrders.existsByCancellationRequestedTrueAndCancellationConfirmedFalse()
                || channelOrders.existsByResolutionIsNotNullAndResolutionSentFalse();
    }

    private static void await(Duration longest, Condition stillBusy) {
        Instant giveUpAt = Instant.now().plus(longest);
        try {
            while (stillBusy.holds() && Instant.now().isBefore(giveUpAt)) {
                Thread.sleep(25);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean holds();
    }
}
