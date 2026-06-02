package com.telecom.simulator.model;

/**
 * AvpCodes — The complete dictionary of AVP codes we use.
 *
 * In Diameter, every field has a numeric code (like HTTP header names).
 * This file is our "Rosetta Stone" — it maps human-readable names
 * to their numeric identifiers defined in RFC 6733 and RFC 4006.
 *
 * Think of this like:
 *   HTTP Content-Type  = 14   (a hypothetical HTTP header number)
 *   Diameter Session-ID = 263  (actual Diameter AVP number)
 *
 * Structure:
 *  - Base protocol AVPs (RFC 6733): codes 1-299
 *  - Credit Control AVPs (RFC 4006): codes 400+
 *  - 3GPP-specific AVPs: codes 1000+, with Vendor-ID 10415
 *
 * IMPORTANT: AVP codes are globally unique (within a Vendor-ID scope).
 * Standard AVPs have Vendor-ID = 0 (or no vendor).
 * 3GPP AVPs have Vendor-ID = 10415.
 */
public final class AvpCodes {

    // Prevent instantiation — this is a constants class only
    private AvpCodes() {}

    // ═══════════════════════════════════════════════════════════════════
    // BASE PROTOCOL AVPs — RFC 6733
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Session-Id (263) — Unique identifier for a Diameter session.
     * Format: "SID;<DiameterIdentity>;<high32>;<low32>"
     * Example: "SID;gateway.telecom.com;1717200000;1"
     * MUST be first AVP in every message that belongs to a session.
     * Type: UTF8String, Mandatory
     */
    public static final int SESSION_ID = 263;

    /**
     * Origin-Host (264) — FQDN of the message sender.
     * Example: "gateway.telecom.com"
     * Type: DiameterIdentity (UTF8String), Mandatory
     */
    public static final int ORIGIN_HOST = 264;

    /**
     * Origin-Realm (296) — Domain of the message sender.
     * Example: "telecom.com"
     * Type: DiameterIdentity (UTF8String), Mandatory
     */
    public static final int ORIGIN_REALM = 296;

    /**
     * Destination-Realm (283) — Where this message should go.
     * Example: "telecom.com"
     * Type: DiameterIdentity (UTF8String), Mandatory
     */
    public static final int DESTINATION_REALM = 283;

    /**
     * Destination-Host (293) — Specific server to send to.
     * Optional — if set, message goes to this specific host.
     * Type: DiameterIdentity (UTF8String)
     */
    public static final int DESTINATION_HOST = 293;

    /**
     * Result-Code (268) — Whether the request succeeded or failed.
     * 2001 = DIAMETER_SUCCESS
     * 4012 = DIAMETER_CREDIT_LIMIT_REACHED
     * Type: Unsigned32 (4 bytes), Mandatory
     */
    public static final int RESULT_CODE = 268;

    /**
     * Auth-Application-Id (258) — Which Diameter application to use.
     * 4 = Diameter Credit Control Application (RFC 4006)
     * 0 = Diameter Base Protocol
     * Type: Unsigned32 (4 bytes), Mandatory
     */
    public static final int AUTH_APPLICATION_ID = 258;

    /**
     * Vendor-Id (266) — Manufacturer's IANA-assigned number.
     * 0 = IETF Standard
     * 10415 = 3GPP
     * 193 = Ericsson
     * Type: Unsigned32 (4 bytes), Mandatory in CER/CEA
     */
    public static final int VENDOR_ID = 266;

    /**
     * Product-Name (269) — Software product name.
     * Example: "TelecomBridgeGateway"
     * Type: UTF8String (no size limit), NOT mandatory
     */
    public static final int PRODUCT_NAME = 269;

    /**
     * Firmware-Revision (267) — Firmware version number.
     * Type: Unsigned32, NOT mandatory
     */
    public static final int FIRMWARE_REVISION = 267;

    /**
     * Supported-Vendor-Id (265) — List of vendor IDs this node supports.
     * Sent in CER to announce 3GPP support.
     * Type: Unsigned32, NOT mandatory, can appear multiple times
     */
    public static final int SUPPORTED_VENDOR_ID = 265;

    /**
     * Vendor-Specific-Application-Id (260) — Grouped AVP.
     * Contains: Vendor-Id + Auth-Application-Id
     * Used in CER/CEA to announce vendor-specific app support (e.g., 3GPP Ro).
     * Type: Grouped, NOT mandatory
     */
    public static final int VENDOR_SPECIFIC_APPLICATION_ID = 260;

    /**
     * Host-IP-Address (257) — IP of this Diameter node.
     * Type: Address (6 bytes: 2-byte family + 4-byte IPv4), Mandatory in CER/CEA
     */
    public static final int HOST_IP_ADDRESS = 257;

    /**
     * Error-Message (281) — Human-readable error description.
     * Optional. Present in error responses.
     * Type: UTF8String
     */
    public static final int ERROR_MESSAGE = 281;

    /**
     * Failed-AVP (279) — Contains copy of the AVP that caused the failure.
     * Helps debugging protocol errors.
     * Type: Grouped
     */
    public static final int FAILED_AVP = 279;

    /**
     * Disconnect-Cause (273) — Why the connection is being terminated.
     * 0 = REBOOTING, 1 = BUSY, 2 = DO_NOT_WANT_TO_TALK_TO_YOU
     * Type: Enumerated (Unsigned32)
     */
    public static final int DISCONNECT_CAUSE = 273;

    // ═══════════════════════════════════════════════════════════════════
    // CREDIT CONTROL AVPs — RFC 4006 (Diameter Ro/Gy Interface)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * CC-Request-Type (416) — What kind of Credit Control Request this is.
     *
     * Values:
     *   1 = INITIAL_REQUEST    → First CCR for a session (subscribe to service)
     *   2 = UPDATE_REQUEST     → Mid-session update (quota running out)
     *   3 = TERMINATION_REQUEST → Session ending (unsubscribe)
     *   4 = EVENT_REQUEST      → One-shot event (SMS, single API call)
     *
     * For our use case: EVENT_REQUEST (4) because each REST call is independent.
     * Type: Enumerated (Unsigned32), Mandatory
     */
    public static final int CC_REQUEST_TYPE = 416;

    // CC-Request-Type values
    public static final int CC_REQUEST_TYPE_INITIAL     = 1;
    public static final int CC_REQUEST_TYPE_UPDATE      = 2;
    public static final int CC_REQUEST_TYPE_TERMINATION = 3;
    public static final int CC_REQUEST_TYPE_EVENT       = 4;

    /**
     * CC-Request-Number (415) — Sequence number within a session.
     * Starts at 0 for INITIAL_REQUEST, increments with each UPDATE.
     * For EVENT_REQUEST, always 0.
     * Type: Unsigned32, Mandatory
     */
    public static final int CC_REQUEST_NUMBER = 415;

    /**
     * Requested-Service-Unit (437) — Grouped AVP.
     * Contains how much quota the client is requesting.
     * Child AVPs:
     *   CC-Total-Octets (421)    → bytes of data
     *   CC-Time (420)            → seconds of usage
     *   CC-Service-Specific-Units (417) → other units
     * Type: Grouped, Optional
     */
    public static final int REQUESTED_SERVICE_UNIT = 437;

    /**
     * Granted-Service-Unit (431) — Grouped AVP.
     * Contains how much quota the server is granting.
     * Same child AVPs as Requested-Service-Unit.
     * Returned in CCA.
     * Type: Grouped, Mandatory in success CCA
     */
    public static final int GRANTED_SERVICE_UNIT = 431;

    /**
     * CC-Total-Octets (421) — Total bytes (upload + download).
     * Type: Unsigned64 (8 bytes)
     */
    public static final int CC_TOTAL_OCTETS = 421;

    /**
     * CC-Input-Octets (412) — Download bytes.
     * Type: Unsigned64 (8 bytes)
     */
    public static final int CC_INPUT_OCTETS = 412;

    /**
     * CC-Output-Octets (414) — Upload bytes.
     * Type: Unsigned64 (8 bytes)
     */
    public static final int CC_OUTPUT_OCTETS = 414;

    /**
     * CC-Time (420) — Usage in seconds (for voice/video calls).
     * Type: Unsigned32 (4 bytes)
     */
    public static final int CC_TIME = 420;

    /**
     * CC-Service-Specific-Units (417) — Generic units (SMS count, API calls).
     * Type: Unsigned64 (8 bytes)
     */
    public static final int CC_SERVICE_SPECIFIC_UNITS = 417;

    /**
     * Subscription-Id (443) — Grouped AVP that identifies the subscriber.
     * Child AVPs:
     *   Subscription-Id-Type (450) → what kind of identifier
     *   Subscription-Id-Data (444) → the actual identifier value
     * Type: Grouped, Mandatory in CCR
     */
    public static final int SUBSCRIPTION_ID = 443;

    /**
     * Subscription-Id-Type (450) — What format the subscriber ID is in.
     *
     * Values:
     *   0 = END_USER_E164     → Phone number (MSISDN format: "919876543210")
     *   1 = END_USER_IMSI     → SIM card number
     *   2 = END_USER_SIP_URI  → SIP URI
     *   3 = END_USER_NAI      → Network Access Identifier
     *   4 = END_USER_PRIVATE  → Private identifier
     *
     * We use: END_USER_E164 (0) for MSISDN (phone number)
     * Type: Enumerated (Unsigned32), Mandatory
     */
    public static final int SUBSCRIPTION_ID_TYPE = 450;

    // Subscription-Id-Type values
    public static final int SUBSCRIPTION_ID_TYPE_E164    = 0;
    public static final int SUBSCRIPTION_ID_TYPE_IMSI    = 1;
    public static final int SUBSCRIPTION_ID_TYPE_SIP_URI = 2;
    public static final int SUBSCRIPTION_ID_TYPE_NAI     = 3;
    public static final int SUBSCRIPTION_ID_TYPE_PRIVATE = 4;

    /**
     * Subscription-Id-Data (444) — The actual subscriber identifier.
     * For E164 type: "919876543210"
     * Type: UTF8String, Mandatory
     */
    public static final int SUBSCRIPTION_ID_DATA = 444;

    /**
     * Service-Context-Id (461) — Identifies the service being charged.
     * Format: "service@domain"
     * Examples:
     *   "32251@3gpp.org"  → PS Data (GPRS/LTE data)
     *   "32260@3gpp.org"  → IMS (VoLTE)
     *   "32274@3gpp.org"  → SMS over IMS
     *   "telecharge@telecom.com" → our custom service
     * Type: UTF8String, Mandatory in CCR
     */
    public static final int SERVICE_CONTEXT_ID = 461;

    /**
     * Validity-Time (448) — How long the granted quota is valid (seconds).
     * After this time, client must send UPDATE_REQUEST to get more quota.
     * Type: Unsigned32
     */
    public static final int VALIDITY_TIME = 448;

    /**
     * CC-Session-Failover (418) — Whether to switch servers on failure.
     * 0 = FAILOVER_NOT_SUPPORTED
     * 1 = FAILOVER_SUPPORTED
     * Type: Enumerated
     */
    public static final int CC_SESSION_FAILOVER = 418;

    /**
     * Multiple-Services-Indicator (455) — Multiple service support.
     * 0 = MULTIPLE_SERVICES_NOT_SUPPORTED
     * 1 = MULTIPLE_SERVICES_SUPPORTED
     * Type: Enumerated
     */
    public static final int MULTIPLE_SERVICES_INDICATOR = 455;

    /**
     * Requested-Action (436) — What action to perform.
     * 0 = DIRECT_DEBITING        → deduct from account now
     * 1 = REFUND_ACCOUNT         → add back to account
     * 2 = CHECK_BALANCE          → just check, don't debit
     * 3 = PRICE_ENQUIRY          → just get price
     * Type: Enumerated, Mandatory when CC-Request-Type is EVENT_REQUEST
     */
    public static final int REQUESTED_ACTION = 436;

    // Requested-Action values
    public static final int REQUESTED_ACTION_DIRECT_DEBITING = 0;
    public static final int REQUESTED_ACTION_REFUND         = 1;
    public static final int REQUESTED_ACTION_CHECK_BALANCE  = 2;
    public static final int REQUESTED_ACTION_PRICE_ENQUIRY  = 3;

    // ═══════════════════════════════════════════════════════════════════
    // APPLICATION IDs — RFC 6733 + RFC 4006
    // ═══════════════════════════════════════════════════════════════════

    /** Base Diameter Protocol — no specific application */
    public static final long APP_BASE_ACCOUNTING = 3L;

    /**
     * Diameter Credit Control Application (DCCA) — RFC 4006
     * This is the main Application-ID for Ro/Gy interface (online charging).
     * Used in ALL CCR/CCA messages.
     */
    public static final long APP_CREDIT_CONTROL = 4L;

    // ═══════════════════════════════════════════════════════════════════
    // COMMAND CODES — RFC 6733 + RFC 4006
    // ═══════════════════════════════════════════════════════════════════

    /** Capabilities-Exchange-Request / Answer — App: Base (0) */
    public static final int CMD_CAPABILITIES_EXCHANGE = 257;

    /** Re-Auth-Request / Answer */
    public static final int CMD_RE_AUTH = 258;

    /** Session-Termination-Request / Answer */
    public static final int CMD_SESSION_TERMINATION = 275;

    /** Abort-Session-Request / Answer */
    public static final int CMD_ABORT_SESSION = 274;

    /** Device-Watchdog-Request / Answer — keep-alive */
    public static final int CMD_DEVICE_WATCHDOG = 280;

    /** Disconnect-Peer-Request / Answer */
    public static final int CMD_DISCONNECT_PEER = 282;

    /**
     * Credit-Control-Request / Answer — RFC 4006
     * The MAIN command we use for billing.
     * Both CCR and CCA share command code 272.
     * R flag in header distinguishes Request (R=1) from Answer (R=0).
     */
    public static final int CMD_CREDIT_CONTROL = 272;

    // ═══════════════════════════════════════════════════════════════════
    // VENDOR IDs
    // ═══════════════════════════════════════════════════════════════════

    /** IETF standard — no vendor */
    public static final long VENDOR_IETF = 0L;

    /** 3GPP vendor ID (IANA assigned) */
    public static final long VENDOR_3GPP = 10415L;

    /** Ericsson vendor ID */
    public static final long VENDOR_ERICSSON = 193L;
}
