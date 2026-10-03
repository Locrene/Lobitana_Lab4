package edu.cit.lobitana.supply;

/** A purchase order we placed with LegacySupply. */
public record PurchaseOrderRef(String poNumber, String supplierSku, int qty, int statusCode) {

    public boolean delivered() {
        return statusCode >= 40;
    }
}
