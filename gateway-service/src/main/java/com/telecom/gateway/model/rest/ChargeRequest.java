package com.telecom.gateway.model.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/**
 * ChargeRequest — The JSON body sent by the API caller.
 *
 * FLOW: REST Client → [this object] → DiameterClient → Simulator
 *
 * Example JSON:
 * {
 *   "msisdn": "919876543210",
 *   "requestedUnits": 100,
 *   "currency": "INR"
 * }
 *
 * Validation annotations ensure bad input is rejected BEFORE
 * it reaches the Diameter protocol layer. Fail fast, fail clearly.
 */
public class ChargeRequest {

    /**
     * MSISDN = Mobile Station International Subscriber Directory Number
     * = the phone number in international format
     * E.g.: "919876543210" = +91 (India) 9876543210
     * Regex: 7-15 digits (ITU E.164 standard)
     */
    @NotBlank(message = "msisdn is required")
    @Pattern(
        regexp = "^[0-9]{7,15}$",
        message = "msisdn must be 7-15 digits (international format, no +)"
    )
    @JsonProperty("msisdn")
    private String msisdn;

    /**
     * How many units the caller wants to charge/grant.
     * "Units" can mean: KB of data, seconds of call, SMS count
     * Must be positive — you can't charge 0 or negative units.
     */
    @NotNull(message = "requestedUnits is required")
    @Min(value = 1, message = "requestedUnits must be at least 1")
    @Max(value = 1_000_000, message = "requestedUnits cannot exceed 1,000,000")
    @JsonProperty("requestedUnits")
    private Long requestedUnits;

    /**
     * ISO 4217 currency code. E.g., "INR", "USD", "EUR"
     * Exactly 3 uppercase letters.
     */
    @NotBlank(message = "currency is required")
    @Pattern(
        regexp = "^[A-Z]{3}$",
        message = "currency must be a 3-letter ISO code (e.g., INR, USD)"
    )
    @JsonProperty("currency")
    private String currency;

    // ─── Constructors ───────────────────────────────────────────────

    public ChargeRequest() {}

    public ChargeRequest(String msisdn, Long requestedUnits, String currency) {
        this.msisdn = msisdn;
        this.requestedUnits = requestedUnits;
        this.currency = currency;
    }

    // ─── Getters & Setters ──────────────────────────────────────────

    public String getMsisdn() { return msisdn; }
    public void setMsisdn(String msisdn) { this.msisdn = msisdn; }

    public Long getRequestedUnits() { return requestedUnits; }
    public void setRequestedUnits(Long requestedUnits) { this.requestedUnits = requestedUnits; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    @Override
    public String toString() {
        // Note: in production logs, msisdn should be masked: 91987***210
        return "ChargeRequest{msisdn='" + msisdn + "', units=" + requestedUnits
               + ", currency='" + currency + "'}";
    }
}
