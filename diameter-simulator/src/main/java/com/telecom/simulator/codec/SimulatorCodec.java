package com.telecom.simulator.codec;

import com.telecom.simulator.model.AvpCodes;
import com.telecom.simulator.model.DiameterHeader;
import com.telecom.simulator.model.DiameterMessage;
import com.telecom.simulator.model.DiameterMessage.Avp;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * DiameterCodec — Encodes Java DiameterMessage objects into binary bytes,
 *                 and decodes binary bytes back into DiameterMessage objects.
 *
 * This is the most critical class in the project. It implements RFC 6733.
 *
 * ENCODING FLOW:
 *   DiameterMessage (Java) → ByteBuffer (binary) → TCP wire
 *
 * DECODING FLOW:
 *   TCP wire → ByteBuffer (binary) → DiameterMessage (Java)
 *
 * KEY CONCEPTS:
 *
 * 1. BIG ENDIAN: Diameter uses network byte order (big-endian).
 *    Java's ByteBuffer defaults to big-endian. ✓
 *
 * 2. UNSIGNED INTEGERS: Java doesn't have unsigned types, but Diameter
 *    uses unsigned 32-bit (Unsigned32). We use long to store them safely.
 *    When writing, cast to int (only lower 32 bits matter on wire).
 *
 * 3. AVP PADDING: Every AVP's total wire size must be multiple of 4 bytes.
 *    Formula: paddedLen = (actualLen + 3) & ~3
 *    Padding bytes are written as zeros.
 *    Padding is NOT counted in AVP Length field but IS counted in total
 *    message length. This is a common mistake that breaks everything.
 *
 * 4. 3-BYTE FIELDS: Diameter uses 3-byte fields for Command Code and AVP Length.
 *    Java doesn't have a 3-byte type. We extract/write them byte-by-byte.
 */
public class SimulatorCodec {

    // ═══════════════════════════════════════════════════════════════════
    // ENCODING — Java Objects → Binary Bytes
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Encodes a complete DiameterMessage into a byte array ready for TCP transmission.
     *
     * PROCESS:
     * 1. Calculate total message size (header + all padded AVPs)
     * 2. Allocate ByteBuffer of that size
     * 3. Write 20-byte header
     * 4. Write each AVP in sequence
     *
     * @param message The Diameter message to encode
     * @return byte[] ready to write to TCP socket
     */
    public byte[] encode(DiameterMessage message) {
        // Step 1: Calculate total size
        int totalSize = DiameterHeader.HEADER_LENGTH; // always 20 bytes
        for (Avp avp : message.getAvps()) {
            totalSize += avp.getPaddedLength(); // each AVP's padded size
        }

        // Step 2: Allocate buffer (big-endian by default in Java)
        ByteBuffer buf = ByteBuffer.allocate(totalSize);

        // Step 3: Write header
        writeHeader(buf, message.getHeader(), totalSize);

        // Step 4: Write each AVP
        for (Avp avp : message.getAvps()) {
            writeAvp(buf, avp);
        }

        return buf.array();
    }

    /**
     * Writes the 20-byte Diameter header into the buffer.
     *
     * Byte layout:
     * Byte 0:      Version (1 byte)
     * Bytes 1-3:   Message Length (3 bytes)
     * Byte 4:      Flags (1 byte: R,P,E,T bits)
     * Bytes 5-7:   Command Code (3 bytes)
     * Bytes 8-11:  Application-ID (4 bytes)
     * Bytes 12-15: Hop-by-Hop Identifier (4 bytes)
     * Bytes 16-19: End-to-End Identifier (4 bytes)
     */
    private void writeHeader(ByteBuffer buf, DiameterHeader header, int totalLength) {
        // Version (1 byte)
        buf.put((byte) header.getVersion());

        // Message Length (3 bytes) — using 3-byte write trick
        writeThreeBytes(buf, totalLength);

        // Flags (1 byte)
        buf.put((byte) header.getFlags());

        // Command Code (3 bytes)
        writeThreeBytes(buf, header.getCommandCode());

        // Application-ID (4 bytes, unsigned 32-bit)
        buf.putInt((int) header.getApplicationId());

        // Hop-by-Hop ID (4 bytes, unsigned 32-bit)
        // This is THE key field for async request matching!
        buf.putInt((int) header.getHopByHopId());

        // End-to-End ID (4 bytes, unsigned 32-bit)
        buf.putInt((int) header.getEndToEndId());
    }

    /**
     * Writes a single AVP into the buffer.
     *
     * Byte layout (no Vendor-ID):
     * Bytes 0-3:   AVP Code (4 bytes)
     * Byte 4:      Flags (1 byte: V,M,P bits)
     * Bytes 5-7:   AVP Length (3 bytes) ← header + data, NOT including padding
     * Bytes 8+:    Data (variable)
     * Padding:     0-3 zero bytes to reach 4-byte boundary
     *
     * With Vendor-ID (V flag set):
     * Bytes 0-3:   AVP Code (4 bytes)
     * Byte 4:      Flags (1 byte)
     * Bytes 5-7:   AVP Length (3 bytes) ← includes Vendor-ID (4 bytes)
     * Bytes 8-11:  Vendor-ID (4 bytes)
     * Bytes 12+:   Data (variable)
     * Padding:     0-3 zero bytes
     */
    private void writeAvp(ByteBuffer buf, Avp avp) {
        // AVP Code (4 bytes)
        buf.putInt(avp.getCode());

        // Flags (1 byte)
        buf.put((byte) avp.getFlags());

        // AVP Length (3 bytes) — the actual length, NOT padded
        writeThreeBytes(buf, avp.getAvpLength());

        // Vendor-ID (4 bytes, only if V flag is set)
        if (avp.hasVendorId()) {
            buf.putInt((int) avp.getVendorId());
        }

        // Data bytes
        if (avp.getData() != null && avp.getData().length > 0) {
            buf.put(avp.getData());
        }

        // Padding — write zero bytes to reach 4-byte alignment
        int padding = avp.getPaddingBytes();
        for (int i = 0; i < padding; i++) {
            buf.put((byte) 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DECODING — Binary Bytes → Java Objects
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Decodes a byte array received from TCP into a DiameterMessage object.
     *
     * PROCESS:
     * 1. Read 20-byte header
     * 2. Read AVPs until we've consumed all bytes
     *
     * @param bytes Raw bytes received from TCP
     * @return Parsed DiameterMessage ready for application logic
     */
    public DiameterMessage decode(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);

        // Step 1: Decode header
        DiameterHeader header = readHeader(buf);
        DiameterMessage message = new DiameterMessage(header);

        // Step 2: Decode AVPs (read until buffer is exhausted)
        while (buf.hasRemaining()) {
            Avp avp = readAvp(buf);
            message.addAvp(avp);
        }

        return message;
    }

    /**
     * Reads the 20-byte header from the buffer.
     * Mirror of writeHeader() — same field order, same sizes.
     */
    private DiameterHeader readHeader(ByteBuffer buf) {
        DiameterHeader header = new DiameterHeader();

        // Version (1 byte)
        header.setVersion(buf.get() & 0xFF);

        // Message Length (3 bytes)
        int messageLength = readThreeBytes(buf);
        header.setMessageLength(messageLength);

        // Flags (1 byte)
        int flags = buf.get() & 0xFF;
        header.setFlags(flags);

        // Command Code (3 bytes)
        int commandCode = readThreeBytes(buf);
        header.setCommandCode(commandCode);

        // Application-ID (4 bytes) — read as int, convert to unsigned long
        long applicationId = buf.getInt() & 0xFFFFFFFFL;
        header.setApplicationId(applicationId);

        // Hop-by-Hop ID (4 bytes) — the async matching key
        long hopByHopId = buf.getInt() & 0xFFFFFFFFL;
        header.setHopByHopId(hopByHopId);

        // End-to-End ID (4 bytes)
        long endToEndId = buf.getInt() & 0xFFFFFFFFL;
        header.setEndToEndId(endToEndId);

        return header;
    }

    /**
     * Reads one AVP from the buffer.
     * Advances the buffer position past the AVP (including padding).
     */
    private Avp readAvp(ByteBuffer buf) {
        Avp avp = new Avp();

        // AVP Code (4 bytes)
        avp.setCode(buf.getInt());

        // Flags (1 byte)
        int flags = buf.get() & 0xFF;
        avp.setFlags(flags);

        // AVP Length (3 bytes) — this is the UNPADDED length
        int avpLength = readThreeBytes(buf);

        // Calculate padded length to know how many bytes to skip after data
        int paddedLength = (avpLength + 3) & ~3;

        // Vendor-ID (4 bytes, only if V flag is set)
        long vendorId = 0;
        if (avp.hasVendorId()) {
            vendorId = buf.getInt() & 0xFFFFFFFFL;
            avp.setVendorId(vendorId);
        }

        // Data bytes:
        // dataLength = avpLength - header bytes
        // Without vendor: header = 4(code) + 1(flags) + 3(length) = 8 bytes
        // With vendor:    header = 8 + 4(vendorId) = 12 bytes
        int headerBytes = avp.hasVendorId() ? 12 : 8;
        int dataLength = avpLength - headerBytes;

        if (dataLength > 0) {
            byte[] data = new byte[dataLength];
            buf.get(data);
            avp.setData(data);
        } else {
            avp.setData(new byte[0]);
        }

        // Skip padding bytes (they're zeros, we don't need them)
        int padding = paddedLength - avpLength;
        if (padding > 0) {
            buf.position(buf.position() + padding);
        }

        return avp;
    }

    // ═══════════════════════════════════════════════════════════════════
    // AVP VALUE HELPERS — Type-safe construction of AVP data
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates an AVP with a UTF8String value.
     * Used for: Session-Id, Origin-Host, Origin-Realm, etc.
     *
     * Example: buildUtf8Avp(AvpCodes.SESSION_ID, true, "SID;host;1;1")
     */
    public Avp buildUtf8Avp(int code, boolean mandatory, String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        return new Avp(code, mandatory, data);
    }

    /**
     * Creates an AVP with an Unsigned32 value (4 bytes).
     * Used for: Result-Code, CC-Request-Type, CC-Request-Number, etc.
     *
     * Even though we pass a long (to handle unsigned values up to 2^32-1),
     * we only write 4 bytes on the wire.
     *
     * Example: buildUint32Avp(AvpCodes.RESULT_CODE, true, 2001L)
     */
    public Avp buildUint32Avp(int code, boolean mandatory, long value) {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt((int) value);
        return new Avp(code, mandatory, buf.array());
    }

    /**
     * Creates an AVP with an Unsigned64 value (8 bytes).
     * Used for: CC-Total-Octets, CC-Input-Octets, CC-Service-Specific-Units.
     *
     * Example: buildUint64Avp(AvpCodes.CC_TOTAL_OCTETS, false, 1024L)
     */
    public Avp buildUint64Avp(int code, boolean mandatory, long value) {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(value);
        return new Avp(code, mandatory, buf.array());
    }

    /**
     * Creates an AVP with an IPv4 Address value.
     * Address AVP format (RFC 6733 §4.3.1):
     *   Bytes 0-1: Address family (0x0001 = IPv4)
     *   Bytes 2-5: 4-byte IPv4 address
     * Total: 6 bytes
     *
     * Used for: Host-IP-Address in CER/CEA
     *
     * Example: buildAddressAvp(AvpCodes.HOST_IP_ADDRESS, true, "127.0.0.1")
     */
    public Avp buildAddressAvp(int code, boolean mandatory, String ipAddress) {
        try {
            InetAddress addr = InetAddress.getByName(ipAddress);
            byte[] addrBytes = addr.getAddress(); // 4 bytes for IPv4

            ByteBuffer buf = ByteBuffer.allocate(6);
            buf.putShort((short) 1); // Address family: 1 = IPv4
            buf.put(addrBytes);

            return new Avp(code, mandatory, buf.array());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP address: " + ipAddress, e);
        }
    }

    /**
     * Creates a Grouped AVP — an AVP whose value is other AVPs.
     * Used for: Subscription-Id, Requested-Service-Unit, Granted-Service-Unit.
     *
     * The data of a Grouped AVP is the encoded bytes of its child AVPs.
     * Child AVPs are also padded to 4-byte boundaries.
     *
     * Example:
     *   buildGroupedAvp(AvpCodes.SUBSCRIPTION_ID, true,
     *       buildUint32Avp(AvpCodes.SUBSCRIPTION_ID_TYPE, true, 0),
     *       buildUtf8Avp(AvpCodes.SUBSCRIPTION_ID_DATA, true, "919876543210"))
     */
    public Avp buildGroupedAvp(int code, boolean mandatory, Avp... childAvps) {
        // Calculate total size of all child AVPs (with padding)
        int totalChildSize = 0;
        for (Avp child : childAvps) {
            totalChildSize += child.getPaddedLength();
        }

        // Encode each child AVP into a buffer
        ByteBuffer buf = ByteBuffer.allocate(totalChildSize);
        for (Avp child : childAvps) {
            writeAvp(buf, child);
        }

        return new Avp(code, mandatory, buf.array());
    }

    // ═══════════════════════════════════════════════════════════════════
    // AVP VALUE READERS — Extract typed values from raw AVP bytes
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Reads a UTF-8 string from an AVP's data bytes.
     * Example: readUtf8(avp) → "919876543210"
     */
    public String readUtf8(Avp avp) {
        return new String(avp.getData(), StandardCharsets.UTF_8);
    }

    /**
     * Reads an unsigned 32-bit integer from an AVP's data bytes.
     * Returns as long to avoid signed/unsigned confusion.
     * Example: readUint32(avp) → 2001L (Result-Code SUCCESS)
     */
    public long readUint32(Avp avp) {
        return ByteBuffer.wrap(avp.getData()).getInt() & 0xFFFFFFFFL;
    }

    /**
     * Reads an unsigned 64-bit integer from an AVP's data bytes.
     * Example: readUint64(avp) → 1024L (bytes granted)
     */
    public long readUint64(Avp avp) {
        return ByteBuffer.wrap(avp.getData()).getLong();
    }

    /**
     * Reads a Grouped AVP's child AVPs.
     * The data bytes of a Grouped AVP are themselves encoded AVPs.
     */
    public DiameterMessage readGrouped(Avp groupedAvp) {
        // Decode the data bytes as if they were AVPs in a message
        // We reuse the decode logic but skip the header (there is none)
        ByteBuffer buf = ByteBuffer.wrap(groupedAvp.getData());
        DiameterMessage pseudo = new DiameterMessage();
        while (buf.hasRemaining()) {
            pseudo.addAvp(readAvp(buf));
        }
        return pseudo;
    }

    // ═══════════════════════════════════════════════════════════════════
    // PRIVATE UTILITIES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Writes a 3-byte big-endian integer into the buffer.
     *
     * Diameter uses 3-byte fields for Command Code and AVP Length.
     * Java has no 3-byte integer type, so we extract bytes manually.
     *
     * Example: writeThreeBytes(buf, 272)
     *   272 = 0x000110
     *   byte[0] = 0x00
     *   byte[1] = 0x01
     *   byte[2] = 0x10
     */
    private void writeThreeBytes(ByteBuffer buf, int value) {
        buf.put((byte) ((value >> 16) & 0xFF)); // most significant byte
        buf.put((byte) ((value >> 8) & 0xFF));  // middle byte
        buf.put((byte) (value & 0xFF));          // least significant byte
    }

    /**
     * Reads 3 bytes from the buffer and assembles them into an int.
     * Mirror of writeThreeBytes.
     */
    private int readThreeBytes(ByteBuffer buf) {
        int b1 = buf.get() & 0xFF; // mask to treat as unsigned
        int b2 = buf.get() & 0xFF;
        int b3 = buf.get() & 0xFF;
        return (b1 << 16) | (b2 << 8) | b3;
    }
}
