package edu.cit.lobitana.seed;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import edu.cit.lobitana.catalog.Product;
import edu.cit.lobitana.catalog.ProductRepository;
import edu.cit.lobitana.inventory.InventoryService;
import edu.cit.lobitana.supply.SupplierCatalogEntry;
import edu.cit.lobitana.supply.SupplierPort;

/**
 * What this shop sells, built from the real LegacySupply catalog.
 *
 * Every partner gets a different catalog and Tiangge rejects a listing whose SupplierSku it cannot find, so
 * the products are derived from the catalog at startup instead of being hardcoded. It runs right after the
 * first heartbeat and before anything is listed, and it is idempotent, so a restart keeps exactly the same SKUs, mapping and
 * reorder policy.
 *
 * Opening stock is deliberately small: a handful of units per product, a reorder point just below it, and
 * one supplier pack per purchase order. That is what makes the whole loop visible within a lab session -
 * stock runs down, the auto-reorder buys, orders are backordered, a delivery arrives and they are filled.
 */
@Component
@Order(2)
class ShopCatalogSetup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShopCatalogSetup.class);

    private static final int PRODUCTS_TO_SELL = 4;
    private static final int OPENING_STOCK = 6;
    private static final int REORDER_POINT = 4;
    private static final int PACKS_PER_PURCHASE_ORDER = 1;

    private final SupplierPort supplier;
    private final ProductRepository products;
    private final InventoryService inventory;

    ShopCatalogSetup(SupplierPort supplier, ProductRepository products, InventoryService inventory) {
        this.supplier = supplier;
        this.products = products;
        this.inventory = inventory;
    }

    @Override
    public void run(ApplicationArguments args) {
        setUpShop();
    }

    /** If LegacySupply was unreachable at startup, try again until the shop has something to sell. */
    @Scheduled(initialDelay = 25_000, fixedDelay = 25_000)
    void retryIfEmpty() {
        if (products.count() < 3) {
            setUpShop();
        }
    }

    private void setUpShop() {
        List<SupplierCatalogEntry> catalog = catalog();
        if (catalog.isEmpty()) {
            log.error("no LegacySupply catalog available yet, so nothing can be sold or listed");
            return;
        }
        catalog.stream().limit(PRODUCTS_TO_SELL).forEach(this::sell);
        log.info("shop is selling {} product(s)", products.count());
    }

    private List<SupplierCatalogEntry> catalog() {
        try {
            List<SupplierCatalogEntry> fresh = supplier.refreshCatalog();
            if (!fresh.isEmpty()) {
                return fresh;
            }
        } catch (RuntimeException ex) {
            log.warn("could not fetch the LegacySupply catalog ({}), falling back to the cached copy",
                    ex.getMessage());
        }
        return supplier.catalog();
    }

    private void sell(SupplierCatalogEntry entry) {
        String sku = shopSku(entry.supplierSku());
        if (!products.existsById(sku)) {
            products.save(new Product(sku, title(entry)));
        }
        inventory.ensureItem(sku, OPENING_STOCK);
        supplier.mapProduct(sku, entry.supplierSku(), REORDER_POINT, PACKS_PER_PURCHASE_ORDER);
    }

    /** Shop SKUs are our own, deliberately not identical to the supplier SKU. */
    private String shopSku(String supplierSku) {
        return "SH-" + supplierSku;
    }

    private String title(SupplierCatalogEntry entry) {
        String description = entry.description() == null || entry.description().isBlank()
                ? entry.supplierSku()
                : entry.description();
        return description.length() <= 80 ? description : description.substring(0, 80);
    }
}
