package edu.cit.lobitana.order;

/**
 * Lab 2 domain event: an order that was waiting for stock reached a final answer.
 *
 * @param fulfilled true when the waiting lines were finally reserved, false when the order was cancelled
 *                  because it still could not be filled
 */
public record BackorderResolvedEvent(long orderId, boolean fulfilled) {
}
