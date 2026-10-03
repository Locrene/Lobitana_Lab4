package edu.cit.lobitana.order;

/** Lab 2 domain event: an order was cancelled and its reservation released. */
public record OrderCancelledEvent(long orderId, String reason) {
}
