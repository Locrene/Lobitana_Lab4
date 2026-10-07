package edu.cit.lobitana.common;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * Tells whether a stock number is on its way to a sales channel at this very moment.
 *
 * Two parts of the application must not act in that instant: a number that was read before a supplier
 * delivery, but arrives after it, would make the channel forget the delivery. So the delivery tracker
 * lets a number that is already travelling arrive before it asks the supplier anything. It lives here
 * because the supplier side and the channel side both need it and must not know each other.
 */
@Component
public class StockAnnouncement {

    private final AtomicInteger onTheWay = new AtomicInteger();

    public void begin() {
        onTheWay.incrementAndGet();
    }

    public void end() {
        onTheWay.decrementAndGet();
    }

    /** Wait, but never longer than this, until no number is travelling. */
    public void awaitNone(Duration longest) {
        Instant giveUpAt = Instant.now().plus(longest);
        try {
            while (onTheWay.get() > 0 && Instant.now().isBefore(giveUpAt)) {
                Thread.sleep(25);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
