package edu.cit.lobitana.supply;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Our record of a LegacySupply purchase order (Lab 3 delivery tracking).
 *
 * LegacySupply never calls us, so status is polled; {@code receivedAt} makes booking a delivery into
 * inventory a once-only operation even if the poller sees status 40 several times.
 */
@Entity
@Table(name = "supplier_purchase_orders")
public class SupplierPurchaseOrder {

    @Id
    private String poNumber;

    private String sku;

    private String supplierSku;

    /** Quantity in the supplier unit of measure (cases). */
    private int qty;

    private int unitsPerPack;

    private int statusCode;

    private String buyerRef;

    private Instant createdAt = Instant.now();

    private Instant lastCheckedAt;

    /**
     * Set once the order is finished: the units were booked into inventory (status 40), or the supplier
     * ended it without delivering (any later status, e.g. 90) and nothing was booked.
     */
    private Instant receivedAt;

    protected SupplierPurchaseOrder() {
        // for JPA
    }

    SupplierPurchaseOrder(String poNumber, String sku, String supplierSku, int qty, int unitsPerPack,
                          int statusCode, String buyerRef) {
        this.poNumber = poNumber;
        this.sku = sku;
        this.supplierSku = supplierSku;
        this.qty = qty;
        this.unitsPerPack = Math.max(1, unitsPerPack);
        this.statusCode = statusCode;
        this.buyerRef = buyerRef;
    }

    public String getPoNumber() {
        return poNumber;
    }

    public String getSku() {
        return sku;
    }

    public String getSupplierSku() {
        return supplierSku;
    }

    public int getQty() {
        return qty;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getBuyerRef() {
        return buyerRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    int unitsOrdered() {
        return qty * unitsPerPack;
    }

    boolean open() {
        return receivedAt == null;
    }

    void updateStatus(int code) {
        this.statusCode = code;
        this.lastCheckedAt = Instant.now();
    }

    void markClosed() {
        this.receivedAt = Instant.now();
    }

    PurchaseOrderRef toRef() {
        return new PurchaseOrderRef(poNumber, supplierSku, qty, statusCode);
    }
}
