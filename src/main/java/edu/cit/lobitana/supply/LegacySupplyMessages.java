package edu.cit.lobitana.supply;

import java.util.List;

/** Response shapes of the LegacySupply partner interface, kept inside the adapter. */
record CatalogResponse(List<SupplierCatalogEntry> items) {
}

record PurchaseOrderAck(String poNumber, int statusCode, String supplierSku, int qty, String uom, String buyerRef) {
}

record PurchaseOrderStatusResponse(String poNumber, int statusCode, String supplierSku, int qty) {
}
