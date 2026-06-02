package com.telecom.simulator.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * DiameterMessage — represents a complete Diameter protocol message.
 *
 * A Diameter message = Header (20 bytes) + list of AVPs (variable length)
 *
 * Think of it like an HTTP message:
 *   HTTP:     [Method + URL + Headers] + [Body]
 *   Diameter: [Header (cmd+flags+ids)] + [AVPs (key-value pairs)]
 *
 * This class is used for ALL message types:
 * - CER/CEA  (Capabilities Exchange)
 * - DWR/DWA  (Device Watchdog)
 * - CCR/CCA  (Credit Control)
 */
public class DiameterMessage {

    private DiameterHeader header;
    private List<Avp> avps;

    // ─── Constructors ────────────────────────────────────────────────

    public DiameterMessage() {
        this.avps = new ArrayList<>();
    }

    public DiameterMessage(DiameterHeader header) {
        this.header = header;
        this.avps = new ArrayList<>();
    }

    // ─── AVP management ──────────────────────────────────────────────

    public DiameterMessage addAvp(Avp avp) {
        this.avps.add(avp);
        return this; // fluent API: msg.addAvp(a).addAvp(b).addAvp(c)
    }

    public List<Avp> getAvps() {
        return Collections.unmodifiableList(avps);
    }

    /**
     * Find a specific AVP by its code.
     * Returns Optional.empty() if not found.
     *
     * Example: message.findAvp(AvpCodes.SESSION_ID)
     */
    public Optional<Avp> findAvp(int avpCode) {
        return avps.stream()
                   .filter(avp -> avp.getCode() == avpCode)
                   .findFirst();
    }

    /**
     * Get all AVPs with a given code (for repeated AVPs like Subscription-Id).
     */
    public List<Avp> findAllAvps(int avpCode) {
        return avps.stream()
                   .filter(avp -> avp.getCode() == avpCode)
                   .toList();
    }

    // ─── Convenience getters from header ─────────────────────────────

    public long getHopByHopId() {
        return header != null ? header.getHopByHopId() : 0;
    }

    public int getCommandCode() {
        return header != null ? header.getCommandCode() : 0;
    }

    public boolean isRequest() {
        return header != null && header.isRequest();
    }

    // ─── Getters & Setters ───────────────────────────────────────────

    public DiameterHeader getHeader() { return header; }
    public void setHeader(DiameterHeader header) { this.header = header; }
    public void setAvps(List<Avp> avps) { this.avps = new ArrayList<>(avps); }

    @Override
    public String toString() {
        return String.format("DiameterMessage{header=%s, avpCount=%d}",
                             header, avps.size());
    }

    // ─── Inner class: AVP ────────────────────────────────────────────

    /**
     * AVP (Attribute-Value Pair) — the building blocks of Diameter messages.
     *
     * WIRE FORMAT (RFC 6733 §4.1):
     *
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                           AVP Code                             |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |V M P r r r r r|                  AVP Length                   |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                        Vendor-ID (optional)                    |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                            Data ...                            |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                       Padding (0-3 bytes)                      |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     *
     * V flag: Vendor-ID field is present (adds 4 bytes before data)
     * M flag: MANDATORY — receiver MUST understand this AVP or reject message
     * P flag: Privacy — encrypted in end-to-end security
     *
     * PADDING RULE (critical for binary correctness!):
     * Total wire length must be multiple of 4.
     * If data is 5 bytes → pad to 8 bytes (add 3 zero bytes).
     * If data is 4 bytes → no padding needed.
     * Padding bytes are NOT included in AVP Length field,
     * but ARE included in total message length calculation.
     */
    public static class Avp {

        // AVP flags (first byte of flags field)
        public static final int FLAG_VENDOR    = 0x80; // V
        public static final int FLAG_MANDATORY = 0x40; // M
        public static final int FLAG_PROTECTED = 0x20; // P

        private int code;        // 4 bytes: identifies what this AVP means
        private int flags;       // 1 byte: V, M, P flags
        private long vendorId;   // 4 bytes: only present when V flag is set
        private byte[] data;     // variable: the actual value

        // ─── Constructors ─────────────────────────────────────────────

        public Avp() {}

        /**
         * Standard AVP (no vendor ID).
         * @param code      AVP code (see AvpCodes constants)
         * @param mandatory true = receiver MUST understand this AVP
         * @param data      raw bytes of the AVP value
         */
        public Avp(int code, boolean mandatory, byte[] data) {
            this.code = code;
            this.flags = mandatory ? FLAG_MANDATORY : 0;
            this.data = data;
        }

        /**
         * Vendor-specific AVP (V flag set, vendor ID present).
         * Used for 3GPP-specific AVPs like CC-Service-Specific-Units.
         */
        public Avp(int code, boolean mandatory, long vendorId, byte[] data) {
            this.code = code;
            this.flags = FLAG_VENDOR | (mandatory ? FLAG_MANDATORY : 0);
            this.vendorId = vendorId;
            this.data = data;
        }

        // ─── Wire length calculations ──────────────────────────────────

        /**
         * AVP Length as it appears in the Length field.
         * = header bytes + data bytes (NOT including padding)
         *
         * Without Vendor-ID: 4 (code) + 1 (flags) + 3 (length) + data = 8 + data
         * With Vendor-ID:    4 (code) + 1 (flags) + 3 (length) + 4 (vendor) + data = 12 + data
         */
        public int getAvpLength() {
            int headerBytes = hasVendorId() ? 12 : 8;
            return headerBytes + (data != null ? data.length : 0);
        }

        /**
         * Padded length = total bytes this AVP occupies on the wire.
         * Round up to nearest 4-byte boundary.
         *
         * Formula: (length + 3) & ~3
         * Examples:
         *   length=8  → (8+3)&~3  = 11&~3 = 8   (no padding)
         *   length=9  → (9+3)&~3  = 12&~3 = 12  (3 bytes padding)
         *   length=10 → (10+3)&~3 = 13&~3 = 12  (2 bytes padding)
         *   length=11 → (11+3)&~3 = 14&~3 = 12  (1 byte padding)
         *   length=12 → (12+3)&~3 = 15&~3 = 12  (no padding)
         */
        public int getPaddedLength() {
            return (getAvpLength() + 3) & ~3;
        }

        /** Returns number of padding bytes needed */
        public int getPaddingBytes() {
            return getPaddedLength() - getAvpLength();
        }

        /** True if V flag is set (vendor ID is present) */
        public boolean hasVendorId() {
            return (flags & FLAG_VENDOR) != 0;
        }

        /** True if M flag is set (mandatory) */
        public boolean isMandatory() {
            return (flags & FLAG_MANDATORY) != 0;
        }

        // ─── Getters & Setters ──────────────────────────────────────────

        public int getCode() { return code; }
        public void setCode(int code) { this.code = code; }

        public int getFlags() { return flags; }
        public void setFlags(int flags) { this.flags = flags; }

        public long getVendorId() { return vendorId; }
        public void setVendorId(long vendorId) { this.vendorId = vendorId; }

        public byte[] getData() { return data; }
        public void setData(byte[] data) { this.data = data; }

        @Override
        public String toString() {
            return String.format("AVP{code=%d, mandatory=%s, dataLen=%d}",
                                 code, isMandatory(), data != null ? data.length : 0);
        }
    }
}
