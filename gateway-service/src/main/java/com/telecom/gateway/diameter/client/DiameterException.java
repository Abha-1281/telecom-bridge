package com.telecom.gateway.diameter.client;

/**
 * DiameterException — Custom exception for Diameter protocol errors.
 *
 * Carries an HTTP status code so the REST layer can return
 * the appropriate HTTP response (503 or 504).
 *
 * Usage:
 *   throw new DiameterException("Server not connected", 503);
 *   throw new DiameterException("Request timed out", 504);
 */
public class DiameterException extends RuntimeException {

    private final int httpStatus;

    public DiameterException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public DiameterException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
