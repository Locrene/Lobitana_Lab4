package edu.cit.lobitana.supply;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Lab 3 mapping: shop SKU -> LegacySupply SupplierSku, plus this shop's reorder policy for it. */
@Entity
@Table(name = "supplier_mappings")
public class SupplierMapping {

    @Id
    private String sku;

    private String supplierSku;

    /** Reorder when available stock falls to or below this. */
    private int reorderThreshold;

    /** How many supplier units of measure (cases) to buy each time. */
    private int reorderQty;

    /** Units per supplier pack, from the catalog PackSize. */
    private int unitsPerPack;

    protected SupplierMapping() {
        // for JPA
    }

    public SupplierMapping(String sku, String supplierSku, int reorderThreshold, int reorderQty, int unitsPerPack) {
        this.sku = sku;
        this.supplierSku = supplierSku;
        this.reorderThreshold = reorderThreshold;
        this.reorderQty = reorderQty;
        this.unitsPerPack = Math.max(1, unitsPerPack);
    }

    public String getSku() {
        return sku;
    }

    public String getSupplierSku() {
        return supplierSku;
    }

    public int getReorderThreshold() {
        return reorderThreshold;
    }

    public int getReorderQty() {
        return reorderQty;
    }

    public int getUnitsPerPack() {
        return unitsPerPack;
    }
}
