package edu.cit.lobitana.channel;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import edu.cit.lobitana.common.AppInstance;

/**
 * Task 1: tell the marketplace this process is alive, and keep telling it.
 *
 * The first heartbeat is the very first remote call this process makes - it runs before the catalog is
 * fetched and before anything is listed, and the feed and the outbox stay quiet until it has been
 * acknowledged. Each reply says when the next one is due, so the rhythm is the marketplace's rather than a
 * number hardcoded here. A failed heartbeat is retried sooner than usual, because an instance that misses
 * 90 seconds counts as offline.
 */
@Component
@Order(1)
class HeartbeatPublisher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatPublisher.class);

    private final TianggeClient client;
    private final TaskScheduler scheduler;
    private final TianggeProperties properties;
    private final AppInstance instance;

    private final AtomicReference<Instant> lastHeartbeatAt = new AtomicReference<>();

    HeartbeatPublisher(TianggeClient client,
                       TaskScheduler scheduler,
                       TianggeProperties properties,
                       AppInstance instance) {
        this.client = client;
        this.scheduler = scheduler;
        this.properties = properties;
        this.instance = instance;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("instance {} is going online on Tiangge", instance.instanceId());
        beat();
        // Nothing else should leave this process before the marketplace knows the instance.
        for (int attempt = 0; attempt < 10 && !online(); attempt++) {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void beat() {
        int nextInSeconds = properties.heartbeatSeconds();
        try {
            HeartbeatResponse response = client.heartbeat();
            if (lastHeartbeatAt.getAndSet(Instant.now()) == null) {
                log.info("first heartbeat acknowledged, instance {} is online", instance.instanceId());
            }
            if (response != null && response.nextHeartbeatSeconds() != null && response.nextHeartbeatSeconds() > 0) {
                nextInSeconds = response.nextHeartbeatSeconds();
            }
            log.debug("heartbeat acknowledged, next one in {}s", nextInSeconds);
        } catch (RuntimeException ex) {
            nextInSeconds = Math.min(nextInSeconds, 5);
            log.warn("heartbeat failed, trying again in {}s: {}", nextInSeconds, ex.getMessage());
        } finally {
            scheduler.schedule(this::beat, Instant.now().plusSeconds(nextInSeconds));
        }
    }

    Instant lastHeartbeatAt() {
        return lastHeartbeatAt.get();
    }

    /** True once the marketplace has acknowledged a heartbeat from this process. */
    boolean online() {
        return lastHeartbeatAt.get() != null;
    }
}
