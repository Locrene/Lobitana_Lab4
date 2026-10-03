package edu.cit.lobitana.supply;

import java.math.BigDecimal;

/** One line of the LegacySupply catalog, in our own words. */
public record SupplierCatalogEntry(String supplierSku, String description, int packSize, BigDecimal unitCost) {
}
