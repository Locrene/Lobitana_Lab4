package edu.cit.lobitana.channel;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * How far this shop has read the Tiangge feed (Task 4, Stage 4).
 *
 * One row, written in the same transaction that records a processed event, so it can never run ahead of
 * the work it describes. It is in the database rather than in memory precisely because a restart must
 * continue from here instead of reading the feed from the beginning.
 */
@Entity
@Table(name = "channel_feed_cursor")
class FeedCursorState {

    static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    private long cursor;

    private Instant updatedAt = Instant.now();

    protected FeedCursorState() {
        // for JPA
    }

    FeedCursorState(long cursor) {
        this.id = SINGLETON_ID;
        this.cursor = cursor;
    }

    long getCursor() {
        return cursor;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void advanceTo(long seq) {
        if (seq > cursor) {
            this.cursor = seq;
            this.updatedAt = Instant.now();
        }
    }
}
