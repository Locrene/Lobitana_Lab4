package edu.cit.lobitana.channel;

/**
 * A Tiangge call failed.
 *
 * {@code retryable} is true for 503 and transport problems - the manual says to back off and try again
 * rather than abandon the request. {@code settled} marks an answer that will never change by repeating it
 * (409 decision_conflict, 409 not_backordered, 404 order_not_found), so the outbox stops asking.
 */
class TianggeApiException extends RuntimeException {

    private final int status;
    private final String errorCode;
    private final boolean retryable;
    private final boolean settled;

    TianggeApiException(int status, String errorCode, String message, boolean retryable, boolean settled) {
        super("Tiangge " + status + " " + (errorCode == null ? "" : errorCode) + ": " + message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.settled = settled;
    }

    static TianggeApiException transport(String operation, Throwable cause) {
        TianggeApiException ex = new TianggeApiException(0, "transport", operation + " failed: " + cause.getMessage(),
                true, false);
        ex.initCause(cause);
        return ex;
    }

    int status() {
        return status;
    }

    String errorCode() {
        return errorCode;
    }

    boolean retryable() {
        return retryable;
    }

    boolean settled() {
        return settled;
    }
}
