package edu.cit.lobitana.supply;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lab 3 behaviour expressed in shop terms: keep the catalog, keep the mapping, buy stock when asked, and
 * remember every purchase order so a delivery can be recognised later.
 */
@Service
class LegacySupplyAdapter implements SupplierPort {

    private static final Logger log = LoggerFactory.getLogger(LegacySupplyAdapter.class);

    private final LegacySupplyClient client;
    private final SupplierCatalogItemRepository catalogItems;
    private final SupplierMappingRepository mappings;
    private final SupplierPurchaseOrderRepository purchaseOrders;
    private final DeliveryTracker deliveries;

    /**
     * One lock per SKU. Two things can decide to restock the same SKU at the same moment - the auto-reorder
     * reacting to a stock event and an order that needs a backorder - and without this they would both see no
     * open purchase order and both buy a pack.
     */
    private final Map<String, Object> reorderLocks = new ConcurrentHashMap<>();

    LegacySupplyAdapter(LegacySupplyClient client,
                        SupplierCatalogItemRepository catalogItems,
                        SupplierMappingRepository mappings,
                        SupplierPurchaseOrderRepository purchaseOrders,
                        DeliveryTracker deliveries) {
        this.deliveries = deliveries;
        this.client = client;
        this.catalogItems = catalogItems;
        this.mappings = mappings;
        this.purchaseOrders = purchaseOrders;
    }

    /**
     * Fetch the catalog and cache it. Called at startup; the cached copy means a restart while
     * LegacySupply is down can still go live with the mapping it already knows.
     */
    @Override
    @Transactional
    public List<SupplierCatalogEntry> refreshCatalog() {
        CatalogResponse response = client.fetchCatalog();
        response.items().forEach(entry -> catalogItems.save(new SupplierCatalogItem(
                entry.supplierSku(), entry.description(), entry.packSize(), entry.unitCost())));
        log.info("LegacySupply catalog refreshed: {} items", response.items().size());
        return response.items();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierCatalogEntry> catalog() {
        return catalogItems.findAllByOrderBySupplierSkuAsc().stream().map(SupplierCatalogItem::toEntry).toList();
    }

    @Override
    @Transactional
    public void mapProduct(String sku, String supplierSku, int reorderThreshold, int reorderQty) {
        int packSize = catalogItems.findById(supplierSku).map(SupplierCatalogItem::getPackSize).orElse(1);
        mappings.save(new SupplierMapping(sku, supplierSku, reorderThreshold, reorderQty, packSize));
        log.info("mapped {} -> supplier sku {} (reorder {} cases at or below {} units, {} units per pack)",
                sku, supplierSku, reorderQty, reorderThreshold, packSize);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> supplierSkuFor(String sku) {
        return mappings.findById(sku).map(SupplierMapping::getSupplierSku);
    }

    @Override
    public boolean deliveryAnswerPending(String sku) {
        return deliveries.answerPending(sku);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenPurchaseOrder(String sku) {
        return !purchaseOrders.findBySkuAndReceivedAtIsNull(sku).isEmpty();
    }

    @Override
    public Optional<PurchaseOrderRef> ensureStockOnTheWay(String sku, String why) {
        synchronized (reorderLocks.computeIfAbsent(sku, key -> new Object())) {
            Optional<SupplierPurchaseOrder> alreadyOpen = purchaseOrders.findBySkuAndReceivedAtIsNull(sku)
                    .stream().findFirst();
            if (alreadyOpen.isPresent() && !deliveries.arrivedMeanwhile(alreadyOpen.get())) {
                return alreadyOpen.map(SupplierPurchaseOrder::toRef);
            }
            // Nothing is open, or what was open has just been booked in: more has to be ordered.
            Optional<SupplierMapping> mapping = mappings.findById(sku);
            if (mapping.isEmpty()) {
                log.warn("no supplier mapping for {}, cannot restock", sku);
                return Optional.empty();
            }
            SupplierMapping policy = mapping.get();
            String buyerRef = buyerRef(sku);
            try {
                PurchaseOrderAck ack = client.placeOrder(
                        policy.getSupplierSku(), policy.getReorderQty(), buyerRef, buyerRef);
                SupplierPurchaseOrder saved = rememberPurchaseOrder(ack, sku, policy, buyerRef);
                log.info("purchase order {} placed for {} ({} cases of {}) because {}",
                        saved.getPoNumber(), sku, policy.getReorderQty(), policy.getSupplierSku(), why);
                return Optional.of(saved.toRef());
            } catch (RuntimeException ex) {
                log.error("could not place a purchase order for {} ({}): {}", sku, why, ex.getMessage());
                return recoverPlacedOrder(sku, policy, buyerRef);
            }
        }
    }

    /**
     * A request can fail on our side although LegacySupply recorded it. Before calling the attempt lost, ask
     * for the order by our own reference, so it is neither lost nor placed a second time later.
     */
    private Optional<PurchaseOrderRef> recoverPlacedOrder(String sku, SupplierMapping policy, String buyerRef) {
        try {
            Optional<PurchaseOrderAck> found = client.findByBuyerRef(buyerRef);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            SupplierPurchaseOrder saved = rememberPurchaseOrder(found.get(), sku, policy, buyerRef);
            log.info("purchase order {} for {} had reached LegacySupply after all", saved.getPoNumber(), sku);
            return Optional.of(saved.toRef());
        } catch (RuntimeException ex) {
            log.warn("could not check whether {} reached LegacySupply: {}", buyerRef, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Single repository save, so it runs in the repository transaction. */
    private SupplierPurchaseOrder rememberPurchaseOrder(PurchaseOrderAck ack, String sku, SupplierMapping policy,
                                                        String buyerRef) {
        String poNumber = ack.poNumber() == null || ack.poNumber().isBlank() ? buyerRef : ack.poNumber();
        return purchaseOrders.save(new SupplierPurchaseOrder(
                poNumber, sku, policy.getSupplierSku(), ack.qty(), unitsPerOrderedUnit(ack.uom(), policy),
                ack.statusCode(), buyerRef));
    }

    /**
     * LegacySupply counts Qty in its own unit of measure and names it in the acknowledgement. A case holds
     * PackSize units; an item sold by the piece is one unit per ordered unit.
     */
    private static int unitsPerOrderedUnit(String uom, SupplierMapping policy) {
        if (uom != null && List.of("EA", "EACH", "PC", "PCS", "UNIT").contains(uom.trim().toUpperCase())) {
            return 1;
        }
        return policy.getUnitsPerPack();
    }

    /**
     * A reference that is unique per reorder intent and short enough for LegacySupply (max 40 chars). It
     * doubles as the X-Request-Id, so a retry after a timeout cannot create a second purchase order.
     */
    private String buyerRef(String sku) {
        String candidate = "LOB-" + sku + "-" + System.currentTimeMillis();
        return candidate.length() <= 40 ? candidate : candidate.substring(candidate.length() - 40);
    }
}
