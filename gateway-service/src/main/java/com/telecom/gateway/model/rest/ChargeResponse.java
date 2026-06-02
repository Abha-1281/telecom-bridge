package com.telecom.gateway.model.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ChargeResponse — What we send back to the API caller.
 *
 * FLOW: Simulator → DiameterClient → [this object] → REST Client
 *
 * Success response:
 * {
 *   "sessionId": "SID;gateway;12345",
 *   "resultCode": 2001,
 *   "resultMessage": "SUCCESS",
 *   "grantedUnits": 100,
 *   "remainingUnits": 900
 * }
 *
 * Error response:
 * {
 *   "resultCode": 4012,
 *   "resultMessage": "INSUFFICIENT_CREDIT",
 *   "error": "User does not have enough balance"
 * }
 *
 * @JsonInclude(NON_NULL) means fields with null value are OMITTED from JSON.
 * So "error" won't appear in success responses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChargeResponse {

    /**
     * Diameter Session-ID — uniquely identifies this charging session.
     * Format: "SID;<origin-host>;<timestamp>;<counter>"
     * Carried across CCR and CCA messages.
     */
    @JsonProperty("sessionId")
    private String sessionId;

    /**
     * Diameter Result-Code AVP value.
     * 2001 = DIAMETER_SUCCESS
     * 4012 = DIAMETER_CREDIT_LIMIT_REACHED (insufficient balance)
     * 3002 = DIAMETER_UNABLE_TO_DELIVER
     * Full list in RFC 6733 Section 7.1
     */
    @JsonProperty("resultCode")
    private int resultCode;

    /**
     * Human-readable version of resultCode.
     * Derived from the Diameter result code.
     */
    @JsonProperty("resultMessage")
    private String resultMessage;

    /**
     * How many units were actually approved/granted.
     * May be less than requestedUnits if balance is low.
     * e.g., requested 1000KB but only 300KB remaining → granted 300
     */
    @JsonProperty("grantedUnits")
    private Long grantedUnits;

    /**
     * How many units remain after this charge.
     * Comes from Diameter CCA (OCS tells us the remaining balance).
     */
    @JsonProperty("remainingUnits")
    private Long remainingUnits;

    /**
     * Only present on error responses.
     * Null (omitted from JSON) on success.
     */
    @JsonProperty("error")
    private String error;

    // ─── Static factory methods (clean construction) ─────────────────

    /**
     * Creates a SUCCESS response.
     * Called when Diameter CCA returns resultCode 2001.
     */
    public static ChargeResponse success(String sessionId, long grantedUnits,
                                          long remainingUnits) {
        ChargeResponse r = new ChargeResponse();
        r.sessionId = sessionId;
        r.resultCode = DiameterResultCode.SUCCESS;
        r.resultMessage = "SUCCESS";
        r.grantedUnits = grantedUnits;
        r.remainingUnits = remainingUnits;
        return r;
    }

    /**
     * Creates an INSUFFICIENT_CREDIT response.
     * Called when Diameter CCA returns resultCode 4012.
     */
    public static ChargeResponse insufficientCredit(String sessionId) {
        ChargeResponse r = new ChargeResponse();
        r.sessionId = sessionId;
        r.resultCode = DiameterResultCode.CREDIT_LIMIT_REACHED;
        r.resultMessage = "INSUFFICIENT_CREDIT";
        r.grantedUnits = 0L;
        r.error = "User does not have sufficient balance for this request";
        return r;
    }

    /**
     * Creates a GATEWAY_TIMEOUT response.
     * Called when Diameter server doesn't respond within timeout.
     * The REST layer returns HTTP 504.
     */
    public static ChargeResponse timeout() {
        ChargeResponse r = new ChargeResponse();
        r.resultCode = 504;
        r.resultMessage = "GATEWAY_TIMEOUT";
        r.error = "Diameter server did not respond within the allowed time";
        return r;
    }

    /**
     * Creates a SERVICE_UNAVAILABLE response.
     * Called when Diameter server is not connected.
     * The REST layer returns HTTP 503.
     */
    public static ChargeResponse unavailable() {
        ChargeResponse r = new ChargeResponse();
        r.resultCode = 503;
        r.resultMessage = "SERVICE_UNAVAILABLE";
        r.error = "Diameter charging server is currently unavailable";
        return r;
    }

    /**
     * Creates a response from any Diameter result code.
     * Used for unknown/other Diameter result codes.
     */
    public static ChargeResponse fromDiameterResult(String sessionId,
                                                     int diameterCode,
                                                     long grantedUnits) {
        ChargeResponse r = new ChargeResponse();
        r.sessionId = sessionId;
        r.resultCode = diameterCode;
        r.resultMessage = DiameterResultCode.toMessage(diameterCode);
        r.grantedUnits = grantedUnits;
        return r;
    }

    // ─── Inner class: Diameter result code constants ──────────────────

    /**
     * Standard Diameter Result-Code values (RFC 6733 §7.1).
     *
     * 1xxx = Informational
     * 2xxx = Success
     * 3xxx = Protocol Errors
     * 4xxx = Transient Failures (try again later)
     * 5xxx = Permanent Failures
     */
    public static class DiameterResultCode {
        public static final int SUCCESS                = 2001;
        public static final int LIMITED_SUCCESS        = 2002;
        public static final int COMMAND_UNSUPPORTED    = 3001;
        public static final int UNABLE_TO_DELIVER      = 3002;
        public static final int REALM_NOT_SERVED       = 3003;
        public static final int TOO_BUSY               = 3004;
        public static final int LOOP_DETECTED          = 3005;
        public static final int REDIRECT_INDICATION    = 3006;
        public static final int APPLICATION_UNSUPPORTED= 3007;
        public static final int INVALID_HDR_BITS       = 3008;
        public static final int INVALID_AVP_BITS       = 3009;
        public static final int UNKNOWN_PEER           = 3010;
        public static final int AUTHENTICATION_REJECTED= 4001;
        public static final int OUT_OF_SPACE           = 4002;
        public static final int ELECTION_LOST          = 4003;
        public static final int CREDIT_LIMIT_REACHED   = 4012; // RFC 4006
        public static final int AVP_UNSUPPORTED        = 5001;
        public static final int UNKNOWN_SESSION_ID     = 5002;
        public static final int AUTHORIZATION_REJECTED = 5003;
        public static final int INVALID_AVP_VALUE      = 5004;
        public static final int MISSING_AVP            = 5005;
        public static final int RESOURCES_EXCEEDED     = 5006;
        public static final int CONTRADICTING_AVPS     = 5007;
        public static final int AVP_NOT_ALLOWED        = 5008;
        public static final int AVP_OCCURS_TOO_MANY_TIMES = 5009;
        public static final int NO_COMMON_APPLICATION  = 5010;
        public static final int UNSUPPORTED_VERSION    = 5011;
        public static final int UNABLE_TO_COMPLY       = 5012;

        public static String toMessage(int code) {
            return switch (code) {
                case SUCCESS                -> "SUCCESS";
                case CREDIT_LIMIT_REACHED   -> "CREDIT_LIMIT_REACHED";
                case TOO_BUSY               -> "SERVER_TOO_BUSY";
                case UNABLE_TO_DELIVER      -> "UNABLE_TO_DELIVER";
                case AUTHORIZATION_REJECTED -> "AUTHORIZATION_REJECTED";
                case UNKNOWN_SESSION_ID     -> "UNKNOWN_SESSION_ID";
                default                     -> "DIAMETER_ERROR_" + code;
            };
        }

        private DiameterResultCode() {} // utility class, no instantiation
    }

    // ─── Getters ─────────────────────────────────────────────────────

    public String getSessionId() { return sessionId; }
    public int getResultCode() { return resultCode; }
    public String getResultMessage() { return resultMessage; }
    public Long getGrantedUnits() { return grantedUnits; }
    public Long getRemainingUnits() { return remainingUnits; }
    public String getError() { return error; }

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setResultCode(int resultCode) { this.resultCode = resultCode; }
    public void setResultMessage(String resultMessage) { this.resultMessage = resultMessage; }
    public void setGrantedUnits(Long grantedUnits) { this.grantedUnits = grantedUnits; }
    public void setRemainingUnits(Long remainingUnits) { this.remainingUnits = remainingUnits; }
    public void setError(String error) { this.error = error; }
}
