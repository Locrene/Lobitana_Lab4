package edu.cit.lobitana.channel;

import java.time.Instant;
import java.util.UUID;

/**
 * A snapshot of the channel, in our own domain terms (Rule 1 allows our own types to be public).
 *
 * @param instanceId         the id this process sends as X-Client-Instance
 * @param startedAt          when this process started
 * @param live               true once a heartbeat and the listings have gone out
 * @param listings           how many listings this process published
 * @param feedCursor         the last feed position that has been fully processed
 * @param lastHeartbeatAt    when the marketplace last confirmed a heartbeat
 * @param lastFeedPollAt     when the feed was last read
 * @param ordersHandled      how many marketplace orders this shop has decided on, all processes together
 * @param pendingDeliveries  decisions, confirmations or resolutions still waiting to reach the marketplace
 */
public record ChannelStatus(UUID instanceId,
                            Instant startedAt,
                            boolean live,
                            long listings,
                            long feedCursor,
                            Instant lastHeartbeatAt,
                            Instant lastFeedPollAt,
                            long ordersHandled,
                            long pendingDeliveries) {
}
