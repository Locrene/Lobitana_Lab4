package edu.cit.lobitana.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import edu.cit.lobitana.catalog.Product;
import edu.cit.lobitana.catalog.ProductRepository;
import edu.cit.lobitana.supply.SupplierPort;

/**
 * Task 2: publish what this shop sells, with the SupplierSku each product is restocked from.
 *
 * A product is only listed if the Lab 3 mapping knows where to buy it, because Tiangge validates the
 * SupplierSku against the LegacySupply catalog. Right after the listings go out, the current stock is sent
 * once so buyers see real numbers immediately; from then on stock is published by inventory events, not
 * from here. If the marketplace is unreachable at startup, the retry below keeps trying until the shop is
 * live.
 */
@Component
class ListingPublisher {

    private static final Logger log = LoggerFactory.getLogger(ListingPublisher.class);

    private final TianggeClient client;
    private final ListingRepository listings;
    private final ProductRepository products;
    private final SupplierPort supplier;
    private final StockSyncListener stockSync;

    private final AtomicBoolean published = new AtomicBoolean(false);

    ListingPublisher(TianggeClient client,
                     ListingRepository listings,
                     ProductRepository products,
                     SupplierPort supplier,
                     StockSyncListener stockSync) {
        this.client = client;
        this.listings = listings;
        this.products = products;
        this.supplier = supplier;
        this.stockSync = stockSync;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    void publishAtStartup() {
        publishOnce();
    }

    /** Keep trying if the marketplace was down when this process started. */
    @Scheduled(initialDelay = 20_000, fixedDelay = 10_000)
    void retryUntilLive() {
        if (!published.get()) {
            publishOnce();
        }
    }

    private void publishOnce() {
        List<Listing> toPublish = buildListings();
        if (toPublish.size() < 3) {
            log.error("only {} product(s) can be listed (a product needs a supplier mapping); "
                    + "the shop needs at least 3", toPublish.size());
            if (toPublish.isEmpty()) {
                return;
            }
        }
        // Our own table first: it is what translates a sellerSku in the feed, whether or not this call works.
        saveListings(toPublish);
        try {
            client.publishListings(toPublish.stream().map(Listing::toPayload).toList());
            published.set(true);
            publishStockSnapshot();
            toPublish.forEach(listing -> log.info("listed {} as {} (restocked from {})",
                    listing.getSku(), listing.getSellerSku(), listing.getSupplierSku()));
        } catch (RuntimeException ex) {
            log.error("could not publish listings yet: {}", ex.getMessage());
        }
    }

    private List<Listing> buildListings() {
        List<Listing> result = new ArrayList<>();
        for (Product product : products.findAll()) {
            Optional<String> supplierSku = supplier.supplierSkuFor(product.getSku());
            if (supplierSku.isEmpty()) {
                log.warn("{} has no supplier mapping, it cannot be listed", product.getSku());
                continue;
            }
            result.add(new Listing(sellerSkuFor(product), product.getSku(), supplierSku.get(), product.getName()));
            if (result.size() == 10) {
                // Tiangge accepts at most 10 listings per call.
                break;
            }
        }
        return result;
    }

    private void saveListings(List<Listing> toPublish) {
        // PUT /listings replaces everything, so our table mirrors that - without ever being empty in
        // between, because the feed poller may be translating an order at this very moment.
        listings.saveAll(toPublish);
        List<String> current = toPublish.stream().map(Listing::getSellerSku).toList();
        listings.findAll().stream()
                .filter(old -> !current.contains(old.getSellerSku()))
                .forEach(listings::delete);
    }

    /** One snapshot at go-live. Afterwards Task 3 handles every change through domain events. */
    private void publishStockSnapshot() {
        stockSync.listingsAccepted(listings.findAllByOrderBySellerSkuAsc().stream().map(Listing::getSku).toList());
    }

    /**
     * The marketplace identifier for a product. Keeping it derived from the shop SKU means a restart
     * publishes exactly the same listings.
     */
    private String sellerSkuFor(Product product) {
        String candidate = "LOB-" + product.getSku();
        return candidate.length() <= 40 ? candidate : candidate.substring(0, 40);
    }

    boolean live() {
        return published.get();
    }
}
