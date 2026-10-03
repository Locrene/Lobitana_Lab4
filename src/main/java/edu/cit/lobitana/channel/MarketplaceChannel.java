package edu.cit.lobitana.channel;

/**
 * The only public door into the channel module (Rule 1).
 *
 * Everything that makes the marketplace work - the HTTP client, the JSON messages, the feed poller, the
 * translators, the outbox - is package-private behind this interface. The rest of the application can ask
 * how the channel is doing and nothing else; it cannot reach into the marketplace, and the Order and
 * Inventory modules never even see this type.
 */
public interface MarketplaceChannel {

    /** What this process is currently doing on the marketplace. */
    ChannelStatus status();
}
