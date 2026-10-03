package edu.cit.lobitana.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Stock for one SKU. {@code onHand} is what is physically in the shop, {@code reserved} is what is
 * promised to accepted orders, and {@code available} - all anyone else is allowed to sell - is the
 * difference. The version column makes concurrent reservations during a flash sale safe.
 */
@Entity
@Table(name = "inventory_items")
public class InventoryItem {

    @Id
    private String sku;

    private int onHand;

    private int reserved;

    @Version
    private long version;

    protected InventoryItem() {
        // for JPA
    }

    public InventoryItem(String sku, int onHand) {
        this.sku = sku;
        this.onHand = onHand;
        this.reserved = 0;
    }

    public String getSku() {
        return sku;
    }

    public int getOnHand() {
        return onHand;
    }

    public int getReserved() {
        return reserved;
    }

    public int available() {
        return Math.max(0, onHand - reserved);
    }

    void reserve(int qty) {
        if (qty > available()) {
            throw new IllegalStateException("cannot reserve " + qty + " of " + sku + ", only " + available() + " available");
        }
        reserved += qty;
    }

    void release(int qty) {
        reserved = Math.max(0, reserved - qty);
    }

    void receive(int qty) {
        onHand += qty;
    }

    /** An accepted order that has shipped consumes both the reservation and the physical stock. */
    void consume(int qty) {
        int taken = Math.min(qty, reserved);
        reserved -= taken;
        onHand = Math.max(0, onHand - taken);
    }
}
