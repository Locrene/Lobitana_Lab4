package edu.cit.lobitana.order;

public enum OrderStatus {
    /** Created, no decision yet. */
    NEW,
    /** Every line reserved from inventory (all-or-nothing). */
    RESERVED,
    /** Cannot be filled and nothing is on the way. */
    REJECTED,
    /** Cannot be filled now, but stock is on order; waiting for a delivery. */
    BACKORDERED,
    /** Cancelled; any reservation has been released back to inventory. */
    CANCELLED
}
