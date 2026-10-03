package edu.cit.lobitana.channel;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One feed event this shop has already dealt with.
 *
 * Tiangge delivers at least once, so the eventId is the primary key and the existence of the row is the
 * answer to "have I seen this before?". Nothing is ever processed twice because of it.
 */
@Entity
@Table(name = "channel_processed_events")
class ProcessedFeedEvent {

    @Id
    private String eventId;

    private long seq;

    private String type;

    private String tianggeOrderId;

    private Instant processedAt = Instant.now();

    protected ProcessedFeedEvent() {
        // for JPA
    }

    ProcessedFeedEvent(String eventId, long seq, String type, String tianggeOrderId) {
        this.eventId = eventId;
        this.seq = seq;
        this.type = type;
        this.tianggeOrderId = tianggeOrderId;
    }

    String getEventId() {
        return eventId;
    }

    long getSeq() {
        return seq;
    }

    String getType() {
        return type;
    }

    String getTianggeOrderId() {
        return tianggeOrderId;
    }

    Instant getProcessedAt() {
        return processedAt;
    }
}
