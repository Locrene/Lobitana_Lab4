package edu.cit.lobitana.channel;

import org.springframework.stereotype.Service;

import edu.cit.lobitana.common.AppInstance;

/**
 * The implementation behind {@link MarketplaceChannel}: the only thing the rest of the application can see
 * of this module, and it is read-only.
 */
@Service
class TianggeChannelService implements MarketplaceChannel {

    private final AppInstance instance;
    private final HeartbeatPublisher heartbeat;
    private final ListingPublisher listingPublisher;
    private final FeedPoller feedPoller;
    private final ChannelBookkeeper bookkeeper;
    private final ListingRepository listings;
    private final ChannelOrderRepository channelOrders;

    TianggeChannelService(AppInstance instance,
                          HeartbeatPublisher heartbeat,
                          ListingPublisher listingPublisher,
                          FeedPoller feedPoller,
                          ChannelBookkeeper bookkeeper,
                          ListingRepository listings,
                          ChannelOrderRepository channelOrders) {
        this.instance = instance;
        this.heartbeat = heartbeat;
        this.listingPublisher = listingPublisher;
        this.feedPoller = feedPoller;
        this.bookkeeper = bookkeeper;
        this.listings = listings;
        this.channelOrders = channelOrders;
    }

    @Override
    public ChannelStatus status() {
        boolean live = listingPublisher.live() && heartbeat.lastHeartbeatAt() != null;
        return new ChannelStatus(
                instance.instanceId(),
                instance.startedAt(),
                live,
                listings.count(),
                bookkeeper.currentCursor(),
                heartbeat.lastHeartbeatAt(),
                feedPoller.lastPollAt(),
                channelOrders.count(),
                channelOrders.countByDecisionSentFalse());
    }
}
