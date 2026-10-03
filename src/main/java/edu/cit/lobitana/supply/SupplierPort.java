package edu.cit.lobitana.supply;

import java.util.List;
import java.util.Optional;

/**
 * The way the rest of the application reaches LegacySupply (the Lab 3 adapter, living inside this app).
 *
 * Callers speak shop SKUs and never see XML, session tokens or status codes.
 */
public interface SupplierPort {

    /** Catalog as last fetched from LegacySupply; each partner has a different one. */
    List<SupplierCatalogEntry> catalog();

    /** Fetch the catalog from LegacySupply now and cache it. */
    List<SupplierCatalogEntry> refreshCatalog();

    /** Record the Lab 3 mapping: this shop SKU is restocked from that SupplierSku. */
    void mapProduct(String sku, String supplierSku, int reorderThreshold, int reorderQty);

    /** The LegacySupply SupplierSku we restock this shop SKU from. */
    Optional<String> supplierSkuFor(String sku);

    /** True when a purchase order for this SKU is placed but not yet delivered. */
    boolean hasOpenPurchaseOrder(String sku);

    /**
     * Make sure stock is on its way for this SKU: returns the existing open purchase order if there is
     * one, otherwise places a new one. Empty when the supplier refused or is unreachable.
     */
    Optional<PurchaseOrderRef> ensureStockOnTheWay(String sku, String why);
}
