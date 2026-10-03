package edu.cit.lobitana.channel;

import java.time.Instant;
import java.util.List;

/**
 * The JSON spoken with Tiangge, and nothing else. All package-private (Rule 1): these shapes belong to
 * the marketplace, not to this shop, so they never leave the channel package.
 */
record HeartbeatRequest(String appName, String startedAt, long uptimeSeconds) {
}

record HeartbeatResponse(String serverTime, Integer nextHeartbeatSeconds) {
}

record ListingPayload(String sellerSku, String title, String supplierSku) {
}

record StockPayload(String sellerSku, int available) {
}

record FeedLine(String sellerSku, int qty) {
}

record FeedBuyer(String name, String city) {
}

record FeedEvent(long seq,
                 String eventId,
                 String type,
                 String orderId,
                 Instant placedAt,
                 Instant decisionDeadline,
                 List<FeedLine> lines,
                 FeedBuyer buyer,
                 Instant cancelledAt,
                 Instant confirmDeadline) {

    boolean placed() {
        return "ORDER_PLACED".equals(type);
    }

    boolean cancelled() {
        return "ORDER_CANCELLED".equals(type);
    }
}

record FeedPage(List<FeedEvent> events, Long nextCursor) {

    List<FeedEvent> safeEvents() {
        return events == null ? List.of() : events;
    }
}

record DecisionRequest(String decision, String shopOrderId, String reason) {
}

record ResolutionRequest(String status) {
}

record CancellationRequest(boolean restocked) {
}
