package edu.cit.lobitana.supply;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A cached copy of one LegacySupply catalog item.
 *
 * Every partner has a different catalog, so the real SupplierSkus are discovered at runtime and kept
 * here; that also means a restart can go live again even if LegacySupply is down at that moment.
 */
@Entity
@Table(name = "supplier_catalog")
public class SupplierCatalogItem {

    @Id
    private String supplierSku;

    private String description;

    private int packSize;

    private BigDecimal unitCost;

    protected SupplierCatalogItem() {
        // for JPA
    }

    public SupplierCatalogItem(String supplierSku, String description, int packSize, BigDecimal unitCost) {
        this.supplierSku = supplierSku;
        this.description = description;
        this.packSize = packSize;
        this.unitCost = unitCost;
    }

    public String getSupplierSku() {
        return supplierSku;
    }

    public String getDescription() {
        return description;
    }

    public int getPackSize() {
        return packSize;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    SupplierCatalogEntry toEntry() {
        return new SupplierCatalogEntry(supplierSku, description, packSize, unitCost);
    }
}
