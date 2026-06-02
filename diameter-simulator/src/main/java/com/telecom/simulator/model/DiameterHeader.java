package com.telecom.simulator.model;

/**
 * DiameterHeader — represents the fixed 20-byte header of every Diameter message.
 *
 * WIRE FORMAT (RFC 6733 §3):
 *
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    Version    |                 Message Length                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | R P E T r r r r|             Command Code                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Application-ID                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      Hop-by-Hop Identifier ← async key        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      End-to-End Identifier                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * FIELD EXPLANATIONS:
 * - Version: Always 1 (Diameter version 1)
 * - Message Length: Total length including header + all AVPs
 * - Flags byte:
 *     R = Request flag (1=request, 0=answer)
 *     P = Proxiable (can be forwarded by proxy)
 *     E = Error (this is an error message)
 *     T = Potentially re-transmitted
 * - Command Code: Identifies message type (e.g., 272=CCR/CCA, 257=CER/CEA)
 * - Application-ID: Which Diameter application (4=Credit Control, 0=Base)
 * - Hop-by-Hop ID: Unique per hop, SAME in request and answer → async matching!
 * - End-to-End ID: Unique per end-to-end message, used for dedup
 */
public class DiameterHeader {

    // Fixed header size — always 20 bytes in Diameter
    public static final int HEADER_LENGTH = 20;

    // Diameter protocol version — always 1
    public static final int VERSION = 1;

    // Flag bit masks
    public static final int FLAG_REQUEST    = 0x80;  // bit 7 — R flag
    public static final int FLAG_PROXIABLE  = 0x40;  // bit 6 — P flag
    public static final int FLAG_ERROR      = 0x20;  // bit 5 — E flag
    public static final int FLAG_RETRANSMIT = 0x10;  // bit 4 — T flag

    // ─── Header fields ────────────────────────────────────────────────

    private int version;           // 1 byte  (always 1)
    private int messageLength;     // 3 bytes (total message size)
    private int flags;             // 1 byte  (R,P,E,T bits)
    private int commandCode;       // 3 bytes (what type of message)
    private long applicationId;    // 4 bytes (which Diameter app)
    private long hopByHopId;       // 4 bytes ← THE KEY for async matching
    private long endToEndId;       // 4 bytes (deduplication)

    // ─── Constructors ────────────────────────────────────────────────

    public DiameterHeader() {
        this.version = VERSION;
    }

    public DiameterHeader(int flags, int commandCode, long applicationId,
                          long hopByHopId, long endToEndId) {
        this.version = VERSION;
        this.flags = flags;
        this.commandCode = commandCode;
        this.applicationId = applicationId;
        this.hopByHopId = hopByHopId;
        this.endToEndId = endToEndId;
    }

    // ─── Convenience flag checkers ───────────────────────────────────

    /** True if this is a Request (R flag set), False if it's an Answer */
    public boolean isRequest() {
        return (flags & FLAG_REQUEST) != 0;
    }

    /** True if this message can be proxied through Diameter agents */
    public boolean isProxiable() {
        return (flags & FLAG_PROXIABLE) != 0;
    }

    /** True if this message reports an error condition */
    public boolean isError() {
        return (flags & FLAG_ERROR) != 0;
    }

    // ─── Builder helpers ─────────────────────────────────────────────

    /** Creates a REQUEST header (R flag + P flag set) */
    public static DiameterHeader request(int commandCode, long applicationId,
                                          long hopByHopId, long endToEndId) {
        return new DiameterHeader(
            FLAG_REQUEST | FLAG_PROXIABLE,
            commandCode, applicationId, hopByHopId, endToEndId
        );
    }

    /** Creates an ANSWER header (no flags set, just proxiable) */
    public static DiameterHeader answer(int commandCode, long applicationId,
                                         long hopByHopId, long endToEndId) {
        return new DiameterHeader(
            FLAG_PROXIABLE,
            commandCode, applicationId, hopByHopId, endToEndId
        );
    }

    // ─── Getters & Setters ───────────────────────────────────────────

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public int getMessageLength() { return messageLength; }
    public void setMessageLength(int messageLength) { this.messageLength = messageLength; }

    public int getFlags() { return flags; }
    public void setFlags(int flags) { this.flags = flags; }

    public int getCommandCode() { return commandCode; }
    public void setCommandCode(int commandCode) { this.commandCode = commandCode; }

    public long getApplicationId() { return applicationId; }
    public void setApplicationId(long applicationId) { this.applicationId = applicationId; }

    public long getHopByHopId() { return hopByHopId; }
    public void setHopByHopId(long hopByHopId) { this.hopByHopId = hopByHopId; }

    public long getEndToEndId() { return endToEndId; }
    public void setEndToEndId(long endToEndId) { this.endToEndId = endToEndId; }

    @Override
    public String toString() {
        return String.format(
            "DiameterHeader{cmd=%d, app=%d, hop=0x%08X, e2e=0x%08X, %s}",
            commandCode, applicationId, hopByHopId, endToEndId,
            isRequest() ? "REQUEST" : "ANSWER"
        );
    }
}
