package edu.cit.lobitana.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import edu.cit.lobitana.order.OrderLineRequest;

/**
 * Turns marketplace vocabulary into shop vocabulary (Rule 1 keeps translators package-private).
 *
 * Tiangge talks in sellerSkus; the Order module only understands shop SKUs. After this class there is
 * nothing marketplace-shaped left in the request, which is how a Tiangge order ends up travelling the
 * exact same path as an order typed into the React UI.
 */
@Component
class OrderTranslator {

    private final ListingRepository listings;

    OrderTranslator(ListingRepository listings) {
        this.listings = listings;
    }

    /** Empty when any line names something this shop does not list. */
    Optional<List<OrderLineRequest>> toShopLines(FeedEvent event) {
        if (event.lines() == null || event.lines().isEmpty()) {
            return Optional.empty();
        }
        List<OrderLineRequest> shopLines = new ArrayList<>();
        for (FeedLine line : event.lines()) {
            Optional<Listing> listing = listings.findById(line.sellerSku());
            if (listing.isEmpty()) {
                return Optional.empty();
            }
            shopLines.add(new OrderLineRequest(listing.get().getSku(), line.qty()));
        }
        return Optional.of(shopLines);
    }
}
