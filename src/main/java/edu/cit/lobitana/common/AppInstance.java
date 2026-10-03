package edu.cit.lobitana.common;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Identity of this running process (Task 1).
 *
 * A fresh UUID is minted every time the JVM starts and is sent as {@code X-Client-Instance} on every
 * outbound call to Tiangge and to LegacySupply. It lives outside the channel package because both the
 * marketplace adapter and the supplier adapter need it.
 */
@Component
public class AppInstance {

    private static final Logger log = LoggerFactory.getLogger(AppInstance.class);

    private final UUID instanceId = UUID.randomUUID();
    private final Instant startedAt = Instant.now();

    public AppInstance() {
        log.info("=== instance {} started at {} ===", instanceId, startedAt);
    }

    public UUID instanceId() {
        return instanceId;
    }

    public String instanceIdHeader() {
        return instanceId.toString();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public long uptimeSeconds() {
        return Duration.between(startedAt, Instant.now()).getSeconds();
    }
}
