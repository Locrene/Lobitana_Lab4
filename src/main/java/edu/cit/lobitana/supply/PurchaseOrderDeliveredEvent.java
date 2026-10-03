package edu.cit.lobitana.supply;

/**
 * Domain event: a LegacySupply purchase order reached status 40 (Delivered) and the units have been
 * booked into inventory. The order module listens to this to retry backorders.
 */
public record PurchaseOrderDeliveredEvent(String poNumber, String sku, String supplierSku, int unitsReceived) {
}
