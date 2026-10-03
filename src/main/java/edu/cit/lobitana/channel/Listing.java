package edu.cit.lobitana.channel;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * What one shop product looks like on the marketplace: the sellerSku Tiangge quotes back to us in orders,
 * and the SupplierSku we restock it from (Task 2).
 *
 * This is the translation table between marketplace vocabulary and shop vocabulary, which is why it lives
 * here and not in the Order or Inventory modules.
 */
@Entity
@Table(name = "channel_listings")
class Listing {

    @Id
    private String sellerSku;

    private String sku;

    private String supplierSku;

    private String title;

    protected Listing() {
        // for JPA
    }

    Listing(String sellerSku, String sku, String supplierSku, String title) {
        this.sellerSku = sellerSku;
        this.sku = sku;
        this.supplierSku = supplierSku;
        this.title = title;
    }

    String getSellerSku() {
        return sellerSku;
    }

    String getSku() {
        return sku;
    }

    String getSupplierSku() {
        return supplierSku;
    }

    String getTitle() {
        return title;
    }

    ListingPayload toPayload() {
        return new ListingPayload(sellerSku, title, supplierSku);
    }
}
