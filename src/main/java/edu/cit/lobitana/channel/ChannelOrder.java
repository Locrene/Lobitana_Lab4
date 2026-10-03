package edu.cit.lobitana.channel;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;

/**
 * The link between one Tiangge order and one order in this shop, plus everything we still owe the
 * marketplace about it.
 *
 * It exists for two reasons. First, the Order module must not carry marketplace identifiers (Rule 2), so
 * the correlation lives here. Second, the Tiangge orderId is the primary key, which makes it the
 * idempotency token: a redelivered order finds a row already here and therefore cannot become a second
 * order in this shop.
 *
 * The {@code ...Sent} flags turn "tell Tiangge" into an outbox: the local decision is committed first and
 * delivered afterwards, so a slow or failing marketplace delays the message without losing it and without
 * re-running any shop logic.
 *
 * Updates write only the columns that changed: the feed thread and the outbox can touch the same row at the
 * same moment (one records a cancellation while the other marks the decision delivered), and neither may
 * overwrite the other's flag with a stale copy.
 */
@Entity
@DynamicUpdate
@Table(name = "channel_orders")
class ChannelOrder {

    @Id
    private String tianggeOrderId;

    /** Our own order id, used as shopOrderId in the decision. Null only between claiming and placing. */
    private Long shopOrderId;

    private String decision;

    private boolean decisionSent;

    private int decisionAttempts;

    private Instant placedAt;

    private Instant decisionDeadline;

    /** SKUs this order was short of, so the decision can be finished after a restart. */
    private String shortSkus;

    private boolean cancellationRequested;

    private boolean cancellationConfirmed;

    private Instant cancelledAt;

    private Instant confirmDeadline;

    private String resolution;

    private boolean resolutionSent;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();

    protected ChannelOrder() {
        // for JPA
    }

    ChannelOrder(String tianggeOrderId, Instant placedAt, Instant decisionDeadline) {
        this.tianggeOrderId = tianggeOrderId;
        this.placedAt = placedAt;
        this.decisionDeadline = decisionDeadline;
    }

    String getTianggeOrderId() {
        return tianggeOrderId;
    }

    Long getShopOrderId() {
        return shopOrderId;
    }

    String getDecision() {
        return decision;
    }

    boolean isDecisionSent() {
        return decisionSent;
    }

    int getDecisionAttempts() {
        return decisionAttempts;
    }

    Instant getDecisionDeadline() {
        return decisionDeadline;
    }

    String getShortSkus() {
        return shortSkus;
    }

    boolean isCancellationRequested() {
        return cancellationRequested;
    }

    boolean isCancellationConfirmed() {
        return cancellationConfirmed;
    }

    Instant getConfirmDeadline() {
        return confirmDeadline;
    }

    String getResolution() {
        return resolution;
    }

    boolean isResolutionSent() {
        return resolutionSent;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    /** True when the shop has made up its mind and the marketplace has been told. */
    boolean settled() {
        return decision != null && decisionSent;
    }

    void decide(long shopOrderId, String decision, String shortSkus) {
        this.shopOrderId = shopOrderId;
        this.decision = decision;
        this.shortSkus = shortSkus;
        this.decisionSent = false;
        touch();
    }

    void claimShopOrder(long shopOrderId, String shortSkus) {
        this.shopOrderId = shopOrderId;
        this.shortSkus = shortSkus;
        touch();
    }

    void decisionDelivered() {
        this.decisionSent = true;
        touch();
    }

    void decisionAttempted() {
        this.decisionAttempts++;
        touch();
    }

    void cancellationRequested(Instant cancelledAt, Instant confirmDeadline) {
        this.cancellationRequested = true;
        this.cancelledAt = cancelledAt;
        this.confirmDeadline = confirmDeadline;
        touch();
    }

    void cancellationDelivered() {
        this.cancellationConfirmed = true;
        touch();
    }

    void resolveAs(String resolution) {
        this.resolution = resolution;
        this.resolutionSent = false;
        touch();
    }

    void resolutionDelivered() {
        this.resolutionSent = true;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
