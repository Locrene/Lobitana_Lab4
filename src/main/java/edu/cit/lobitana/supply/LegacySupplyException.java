package edu.cit.lobitana.supply;

/** A LegacySupply call failed. {@code retryable} separates "try again" from "we sent something wrong". */
class LegacySupplyException extends RuntimeException {

    private final int httpStatus;
    private final String code;
    private final boolean retryable;
    private final boolean sessionExpired;

    LegacySupplyException(int httpStatus, String code, String message, boolean retryable, boolean sessionExpired) {
        super("LegacySupply " + httpStatus + " " + (code == null ? "" : code) + ": " + message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.retryable = retryable;
        this.sessionExpired = sessionExpired;
    }

    static LegacySupplyException transport(String message, Throwable cause) {
        LegacySupplyException ex = new LegacySupplyException(0, "TRANSPORT", message, true, false);
        ex.initCause(cause);
        return ex;
    }

    int httpStatus() {
        return httpStatus;
    }

    String code() {
        return code;
    }

    boolean retryable() {
        return retryable;
    }

    boolean sessionExpired() {
        return sessionExpired;
    }
}
