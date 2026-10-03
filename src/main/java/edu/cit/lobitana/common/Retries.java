package edu.cit.lobitana.common;

import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tiny retry helper with exponential backoff.
 *
 * Both remote systems are documented to slow down or fail (Tiangge 503 {@code unavailable},
 * LegacySupply E-SYS-50/E-SYS-99/E-RATE-03). Transient failures are retried here instead of being
 * turned into a missed deadline; permanent failures (4xx that we caused) are rethrown immediately so
 * we do not hammer the service.
 */
public final class Retries {

    private static final Logger log = LoggerFactory.getLogger(Retries.class);

    private Retries() {
    }

    public static <T> T withBackoff(String operation,
                                    int maxAttempts,
                                    long initialDelayMillis,
                                    Supplier<T> call,
                                    Predicate<RuntimeException> retryable) {
        RuntimeException last = null;
        long delay = initialDelayMillis;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException ex) {
                last = ex;
                if (!retryable.test(ex) || attempt == maxAttempts) {
                    throw ex;
                }
                log.warn("{} failed on attempt {}/{} ({}); retrying in {} ms",
                        operation, attempt, maxAttempts, ex.getMessage(), delay);
                sleep(delay);
                delay = Math.min(delay * 2, 8_000L);
            }
        }
        throw last;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off", ie);
        }
    }
}
