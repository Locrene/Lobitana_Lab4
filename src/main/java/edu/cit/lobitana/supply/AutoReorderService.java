package edu.cit.lobitana.supply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import edu.cit.lobitana.inventory.InventoryService;
import edu.cit.lobitana.inventory.StockLevelChangedEvent;

/**
 * Lab 3 auto-reorder, driven by the same inventory event the marketplace listens to.
 *
 * Stock dropping to the reorder point is a fact about inventory, so the trigger is the domain event, not
 * a timer. The scheduled sweep is only a safety net: it catches a SKU that was already low while the
 * application was down and therefore never fired an event in this process.
 */
@Component
class AutoReorderService {

    private static final Logger log = LoggerFactory.getLogger(AutoReorderService.class);

    private final SupplierPort supplier;
    private final SupplierMappingRepository mappings;
    private final InventoryService inventory;

    AutoReorderService(SupplierPort supplier, SupplierMappingRepository mappings, InventoryService inventory) {
        this.supplier = supplier;
        this.mappings = mappings;
        this.inventory = inventory;
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void onStockChanged(StockLevelChangedEvent event) {
        mappings.findById(event.sku()).ifPresent(policy -> considerReorder(policy, event.available()));
    }

    /** Safety net for stock that was already low before this process started. */
    @Scheduled(initialDelay = 45_000, fixedDelay = 60_000)
    void sweepLowStock() {
        mappings.findAll().forEach(policy -> considerReorder(policy, inventory.available(policy.getSku())));
    }

    private void considerReorder(SupplierMapping policy, int available) {
        if (available > policy.getReorderThreshold()) {
            return;
        }
        if (supplier.hasOpenPurchaseOrder(policy.getSku())) {
            log.debug("{} is low ({} available) but a purchase order is already open", policy.getSku(), available);
            return;
        }
        log.info("{} is down to {} units (reorder point {}), buying from LegacySupply",
                policy.getSku(), available, policy.getReorderThreshold());
        supplier.ensureStockOnTheWay(policy.getSku(), "stock at " + available + " units");
    }
}
