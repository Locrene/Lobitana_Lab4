package edu.cit.lobitana.channel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nobody tells this shop that an order arrived, so it looks (Task 4).
 *
 * Every few seconds the feed is read from the stored cursor and drained page by page, which is what lets a
 * flash sale and a restart backlog be handled the same way as a quiet minute. Two rules keep it honest:
 * the cursor only ever moves because an event was fully handled and committed, and a failing event stops
 * the cycle instead of being skipped - so nothing is lost. An event that keeps failing many times in a row
 * is eventually stepped over, because one bad event must not block every order behind it.
 */
@Component
class FeedPoller {

    private static final Logger log = LoggerFactory.getLogger(FeedPoller.class);

    private static final int MAX_ATTEMPTS_PER_EVENT = 6;
    private static final Path FEED_WAS_RESET = Path.of("data", "feed-was-reset");

    private final TianggeClient client;
    private final FeedEventProcessor processor;
    private final ChannelBookkeeper bookkeeper;
    private final TianggeProperties properties;
    private final HeartbeatPublisher heartbeat;

    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastPollAt = new AtomicReference<>();
    private boolean markerChecked;

    FeedPoller(TianggeClient client,
               FeedEventProcessor processor,
               ChannelBookkeeper bookkeeper,
               TianggeProperties properties,
               HeartbeatPublisher heartbeat) {
        this.heartbeat = heartbeat;
        this.client = client;
        this.processor = processor;
        this.bookkeeper = bookkeeper;
        this.properties = properties;
    }

    @Scheduled(initialDelay = 6_000, fixedDelayString = "${tiangge.poll-interval-ms:4000}")
    void poll() {
        if (!heartbeat.online()) {
            // The marketplace wants a heartbeat before any other call from a new instance.
            return;
        }
        try {
            startOverIfAsked();
            drain();
        } catch (RuntimeException ex) {
            // The marketplace is allowed to be slow or down; the next tick simply tries again.
            log.warn("could not read the Tiangge feed this time: {}", ex.getMessage());
        }
    }

    /**
     * The cursor is never moved back by this application on its own. The one exception is deliberate and
     * manual: after the marketplace record has been reset on the self-check page, a file named
     * data/feed-was-reset tells the next start that the feed begins again. The file is removed at once, so
     * it works exactly one time.
     */
    private void startOverIfAsked() {
        if (markerChecked) {
            return;
        }
        markerChecked = true;
        try {
            if (Files.deleteIfExists(FEED_WAS_RESET)) {
                bookkeeper.startFeedOver();
                log.warn("{} was present: the marketplace feed was reset, reading it from the beginning once",
                        FEED_WAS_RESET);
            }
        } catch (IOException ex) {
            log.warn("could not check {}: {}", FEED_WAS_RESET, ex.getMessage());
        }
    }

    private void drain() {
        long cursor = bookkeeper.currentCursor();
        for (int page = 1; page <= properties.maxPagesPerCycle(); page++) {
            FeedPage fetched = client.fetchFeed(cursor, properties.feedPageSize());
            lastPollAt.set(Instant.now());
            List<FeedEvent> events = fetched.safeEvents();
            if (events.isEmpty()) {
                return;
            }
            log.info("feed returned {} event(s) after cursor {}", events.size(), cursor);
            if (!handle(events)) {
                return;
            }
            long advanced = bookkeeper.currentCursor();
            if (advanced <= cursor) {
                return;
            }
            cursor = advanced;
            if (events.size() < properties.feedPageSize()) {
                return;
            }
        }
    }

    /** @return true when the whole page was handled and it is safe to ask for the next one */
    private boolean handle(List<FeedEvent> events) {
        for (FeedEvent event : events) {
            try {
                processor.process(event);
                failures.remove(event.eventId());
            } catch (RuntimeException ex) {
                int attempts = failures.merge(event.eventId() == null ? "unknown" : event.eventId(), 1, Integer::sum);
                if (attempts >= MAX_ATTEMPTS_PER_EVENT) {
                    log.error("giving up on feed event {} (seq {}) after {} attempts: {}",
                            event.eventId(), event.seq(), attempts, ex.getMessage());
                    bookkeeper.acknowledge(event);
                    continue;
                }
                log.warn("event {} (seq {}) failed on attempt {}, stopping this cycle so nothing is skipped: {}",
                        event.eventId(), event.seq(), attempts, ex.getMessage());
                return false;
            }
        }
        return true;
    }

    Instant lastPollAt() {
        return lastPollAt.get();
    }
}
