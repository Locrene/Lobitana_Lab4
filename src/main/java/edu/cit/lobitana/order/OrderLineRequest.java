package edu.cit.lobitana.order;

/** One requested line, in shop SKUs. The only thing a caller needs to speak. */
public record OrderLineRequest(String sku, int qty) {
}
