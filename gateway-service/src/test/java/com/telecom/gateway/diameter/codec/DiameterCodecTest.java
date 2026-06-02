package com.telecom.gateway.diameter.codec;

import com.telecom.gateway.model.diameter.AvpCodes;
import com.telecom.gateway.model.diameter.DiameterHeader;
import com.telecom.gateway.model.diameter.DiameterMessage;
import com.telecom.gateway.model.diameter.DiameterMessage.Avp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DiameterCodecTest — Exhaustive tests for AVP encoding/decoding.
 *
 * WHY THESE TESTS MATTER:
 * If even one byte is wrong in the Diameter header or AVP encoding,
 * the Diameter server will silently drop or reject our messages.
 * Unlike HTTP where you get a readable error, Diameter errors are
 * cryptic binary responses. These tests are our first line of defense.
 *
 * TEST CATEGORIES:
 * 1. AVP Padding — the most common source of bugs
 * 2. AVP encoding round-trips — encode then decode = same value
 * 3. Header encoding — correct byte positions
 * 4. Full message encoding — complete CCR message
 * 5. Grouped AVP — child AVPs encoded correctly
 */
@DisplayName("Diameter Codec Tests")
class DiameterCodecTest {

    private DiameterCodec codec;

    @BeforeEach
    void setUp() {
        codec = new DiameterCodec();
    }

    // ═══════════════════════════════════════════════════════════════════
    // PADDING TESTS — The most critical correctness requirement
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AVP Padding Calculations")
    class PaddingTests {

        /**
         * PADDING RULE: Every AVP's total wire size must be multiple of 4.
         *
         * AVP header = 8 bytes (code:4 + flags:1 + length:3)
         * If data = 0 bytes → total = 8 → 8 % 4 = 0 → NO padding needed
         * If data = 1 byte  → total = 9 → pad to 12 → 3 bytes padding
         * If data = 2 bytes → total = 10 → pad to 12 → 2 bytes padding
         * If data = 3 bytes → total = 11 → pad to 12 → 1 byte padding
         * If data = 4 bytes → total = 12 → 12 % 4 = 0 → NO padding needed
         * If data = 5 bytes → total = 13 → pad to 16 → 3 bytes padding
         */
        @Test
        @DisplayName("0-byte data → 8 bytes total, 0 padding")
        void zeroByteData_nopadding() {
            Avp avp = new Avp(AvpCodes.RESULT_CODE, true, new byte[0]);
            assertEquals(8, avp.getAvpLength(),    "AVP length should be 8 (header only)");
            assertEquals(8, avp.getPaddedLength(), "Padded length should be 8 (already aligned)");
            assertEquals(0, avp.getPaddingBytes(), "No padding needed");
        }

        @Test
        @DisplayName("1-byte data → 9 bytes total → pad to 12 (3 bytes padding)")
        void oneByteData_threePadding() {
            Avp avp = new Avp(AvpCodes.RESULT_CODE, true, new byte[]{0x01});
            assertEquals(9,  avp.getAvpLength(),    "AVP length = 8 header + 1 data");
            assertEquals(12, avp.getPaddedLength(), "Must pad to 12 (next multiple of 4)");
            assertEquals(3,  avp.getPaddingBytes(), "Need 3 padding bytes");
        }

        @Test
        @DisplayName("2-byte data → 10 bytes total → pad to 12 (2 bytes padding)")
        void twoByteData_twoPadding() {
            Avp avp = new Avp(AvpCodes.RESULT_CODE, true, new byte[]{0x01, 0x02});
            assertEquals(10, avp.getAvpLength());
            assertEquals(12, avp.getPaddedLength());
            assertEquals(2,  avp.getPaddingBytes());
        }

        @Test
        @DisplayName("3-byte data → 11 bytes total → pad to 12 (1 byte padding)")
        void threeByteData_onePadding() {
            Avp avp = new Avp(AvpCodes.RESULT_CODE, true, new byte[]{0x01, 0x02, 0x03});
            assertEquals(11, avp.getAvpLength());
            assertEquals(12, avp.getPaddedLength());
            assertEquals(1,  avp.getPaddingBytes());
        }

        @Test
        @DisplayName("4-byte data → 12 bytes total → no padding needed")
        void fourByteData_noPadding() {
            Avp avp = new Avp(AvpCodes.RESULT_CODE, true, new byte[]{0x01, 0x02, 0x03, 0x04});
            assertEquals(12, avp.getAvpLength());
            assertEquals(12, avp.getPaddedLength());
            assertEquals(0,  avp.getPaddingBytes());
        }

        @Test
        @DisplayName("5-byte data → 13 bytes total → pad to 16 (3 bytes padding)")
        void fiveByteData_threePadding() {
            // "INR" = 3 chars, but "hello" = 5 chars
            Avp avp = new Avp(100, true, new byte[]{1, 2, 3, 4, 5});
            assertEquals(13, avp.getAvpLength());
            assertEquals(16, avp.getPaddedLength());
            assertEquals(3,  avp.getPaddingBytes());
        }

        @Test
        @DisplayName("12-byte data → 20 bytes total → no padding (already multiple of 4)")
        void twelveByteData_noPadding() {
            Avp avp = new Avp(100, true, new byte[12]);
            assertEquals(20, avp.getAvpLength());
            assertEquals(20, avp.getPaddedLength());
            assertEquals(0,  avp.getPaddingBytes());
        }

        @Test
        @DisplayName("String 'INR' (3 bytes) → 11 total → pad to 12")
        void stringInr_onePadding() {
            // "INR" in UTF-8 is 3 bytes
            Avp avp = codec.buildUtf8Avp(100, true, "INR");
            assertEquals(3, avp.getData().length, "INR should be 3 UTF-8 bytes");
            assertEquals(11, avp.getAvpLength(),   "8 header + 3 data = 11");
            assertEquals(12, avp.getPaddedLength(),"Must pad to 12");
        }

        @Test
        @DisplayName("MSISDN '919876543210' (12 bytes) → 20 total → no padding")
        void msisdnString_noPadding() {
            // "919876543210" = 12 ASCII characters = 12 bytes in UTF-8
            Avp avp = codec.buildUtf8Avp(AvpCodes.SUBSCRIPTION_ID_DATA, true, "919876543210");
            assertEquals(12, avp.getData().length,  "MSISDN should be 12 bytes");
            assertEquals(20, avp.getAvpLength(),    "8 header + 12 data = 20");
            assertEquals(20, avp.getPaddedLength(), "20 is multiple of 4, no padding");
            assertEquals(0,  avp.getPaddingBytes(), "No padding bytes");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ROUND-TRIP TESTS — encode then decode = original value
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AVP Round-Trip (Encode → Decode)")
    class RoundTripTests {

        @Test
        @DisplayName("Unsigned32 AVP round-trip: Result-Code 2001")
        void uint32RoundTrip_resultCode() {
            // Encode
            Avp encoded = codec.buildUint32Avp(AvpCodes.RESULT_CODE, true, 2001L);

            // Build a simple message with just this AVP
            DiameterMessage msg = buildTestMessage(encoded);
            byte[] bytes = codec.encode(msg);

            // Decode
            DiameterMessage decoded = codec.decode(bytes);
            Avp decodedAvp = decoded.findAvp(AvpCodes.RESULT_CODE)
                                    .orElseThrow(() -> new AssertionError("RESULT_CODE AVP not found"));

            assertEquals(2001L, codec.readUint32(decodedAvp),
                "Result-Code should round-trip correctly");
        }

        @Test
        @DisplayName("UTF8String AVP round-trip: MSISDN")
        void utf8RoundTrip_msisdn() {
            String msisdn = "919876543210";

            Avp encoded = codec.buildUtf8Avp(AvpCodes.SUBSCRIPTION_ID_DATA, true, msisdn);
            DiameterMessage msg = buildTestMessage(encoded);
            byte[] bytes = codec.encode(msg);

            DiameterMessage decoded = codec.decode(bytes);
            Avp decodedAvp = decoded.findAvp(AvpCodes.SUBSCRIPTION_ID_DATA)
                                    .orElseThrow();

            assertEquals(msisdn, codec.readUtf8(decodedAvp),
                "MSISDN should round-trip correctly");
        }

        @Test
        @DisplayName("UTF8String AVP round-trip: Session-Id with special chars")
        void utf8RoundTrip_sessionId() {
            String sessionId = "SID;gateway.telecom.com;1717200000;42";

            Avp encoded = codec.buildUtf8Avp(AvpCodes.SESSION_ID, true, sessionId);
            DiameterMessage msg = buildTestMessage(encoded);
            byte[] bytes = codec.encode(msg);

            DiameterMessage decoded = codec.decode(bytes);
            Avp decodedAvp = decoded.findAvp(AvpCodes.SESSION_ID).orElseThrow();

            assertEquals(sessionId, codec.readUtf8(decodedAvp));
        }

        @Test
        @DisplayName("Unsigned64 AVP round-trip: CC-Total-Octets 1048576 (1MB)")
        void uint64RoundTrip_octets() {
            long oneMB = 1024L * 1024L; // 1,048,576 bytes

            Avp encoded = codec.buildUint64Avp(AvpCodes.CC_TOTAL_OCTETS, false, oneMB);
            DiameterMessage msg = buildTestMessage(encoded);
            byte[] bytes = codec.encode(msg);

            DiameterMessage decoded = codec.decode(bytes);
            Avp decodedAvp = decoded.findAvp(AvpCodes.CC_TOTAL_OCTETS).orElseThrow();

            assertEquals(oneMB, codec.readUint64(decodedAvp),
                "CC-Total-Octets should round-trip correctly");
        }

        @Test
        @DisplayName("Large Unsigned32 value (near max): 4294967295")
        void uint32RoundTrip_maxValue() {
            long maxUint32 = 0xFFFFFFFFL; // max unsigned 32-bit value

            Avp encoded = codec.buildUint32Avp(AvpCodes.VENDOR_ID, false, maxUint32);
            DiameterMessage msg = buildTestMessage(encoded);
            byte[] bytes = codec.encode(msg);

            DiameterMessage decoded = codec.decode(bytes);
            Avp decodedAvp = decoded.findAvp(AvpCodes.VENDOR_ID).orElseThrow();

            assertEquals(maxUint32, codec.readUint32(decodedAvp),
                "Should handle max unsigned 32-bit value without sign issues");
        }

        @Test
        @DisplayName("Hop-by-Hop ID preserved across encode/decode")
        void hopByHopId_preserved() {
            long hopByHop = 0xABCD1234L;

            DiameterHeader header = DiameterHeader.request(
                AvpCodes.CMD_CREDIT_CONTROL,
                AvpCodes.APP_CREDIT_CONTROL,
                hopByHop, 0L
            );
            DiameterMessage msg = new DiameterMessage(header);
            byte[] bytes = codec.encode(msg);

            DiameterMessage decoded = codec.decode(bytes);
            assertEquals(hopByHop, decoded.getHopByHopId(),
                "Hop-by-Hop ID must survive encode/decode for async matching");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HEADER ENCODING TESTS — Correct byte positions in binary output
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Header Binary Encoding")
    class HeaderEncodingTests {

        @Test
        @DisplayName("Version byte is 1 at position 0")
        void versionByteIsOne() {
            DiameterMessage msg = buildMessageWithHeader(
                AvpCodes.CMD_CREDIT_CONTROL, AvpCodes.APP_CREDIT_CONTROL, 1L, 2L
            );
            byte[] bytes = codec.encode(msg);

            assertEquals(1, bytes[0] & 0xFF, "Version must be 1 (Diameter v1)");
        }

        @Test
        @DisplayName("Message length at bytes 1-3 equals actual total size")
        void messageLengthCorrect() {
            // Build message with one 4-byte Uint32 AVP (12 bytes padded)
            Avp avp = codec.buildUint32Avp(AvpCodes.RESULT_CODE, true, 2001L);
            DiameterMessage msg = new DiameterMessage(
                DiameterHeader.answer(AvpCodes.CMD_CREDIT_CONTROL, AvpCodes.APP_CREDIT_CONTROL, 1L, 1L)
            );
            msg.addAvp(avp);

            byte[] bytes = codec.encode(msg);

            // Read 3-byte length from bytes 1-3
            int encodedLength = ((bytes[1] & 0xFF) << 16)
                              | ((bytes[2] & 0xFF) << 8)
                              |  (bytes[3] & 0xFF);

            int expectedLength = 20 + 12; // 20 header + 12 (8 header + 4 data)
            assertEquals(expectedLength, encodedLength,
                "Message length field must be 20 (header) + 12 (AVP)");
        }

        @Test
        @DisplayName("Request flag (R=1) set correctly for CCR")
        void requestFlagSetForRequest() {
            DiameterHeader header = DiameterHeader.request(
                AvpCodes.CMD_CREDIT_CONTROL, AvpCodes.APP_CREDIT_CONTROL, 1L, 1L
            );
            DiameterMessage msg = new DiameterMessage(header);
            byte[] bytes = codec.encode(msg);

            // Flags byte is at position 4
            int flags = bytes[4] & 0xFF;
            assertTrue((flags & DiameterHeader.FLAG_REQUEST) != 0,
                "R flag must be set in CCR (request)");
        }

        @Test
        @DisplayName("Request flag (R=0) NOT set for CCA answer")
        void requestFlagNotSetForAnswer() {
            DiameterHeader header = DiameterHeader.answer(
                AvpCodes.CMD_CREDIT_CONTROL, AvpCodes.APP_CREDIT_CONTROL, 1L, 1L
            );
            DiameterMessage msg = new DiameterMessage(header);
            byte[] bytes = codec.encode(msg);

            int flags = bytes[4] & 0xFF;
            assertFalse((flags & DiameterHeader.FLAG_REQUEST) != 0,
                "R flag must NOT be set in CCA (answer)");
        }

        @Test
        @DisplayName("Command Code 272 encoded at bytes 5-7")
        void commandCodeAtBytes5to7() {
            DiameterMessage msg = buildMessageWithHeader(
                272, AvpCodes.APP_CREDIT_CONTROL, 1L, 1L
            );
            byte[] bytes = codec.encode(msg);

            // Command code is 3 bytes at positions 5, 6, 7
            int commandCode = ((bytes[5] & 0xFF) << 16)
                            | ((bytes[6] & 0xFF) << 8)
                            |  (bytes[7] & 0xFF);

            assertEquals(272, commandCode, "Credit-Control command code must be 272");
        }

        @Test
        @DisplayName("Application-ID 4 encoded at bytes 8-11")
        void applicationIdAtBytes8to11() {
            DiameterMessage msg = buildMessageWithHeader(
                AvpCodes.CMD_CREDIT_CONTROL, 4L, 1L, 1L
            );
            byte[] bytes = codec.encode(msg);

            // Application-ID is 4 bytes at positions 8-11
            long appId = ((bytes[8]  & 0xFFL) << 24)
                       | ((bytes[9]  & 0xFFL) << 16)
                       | ((bytes[10] & 0xFFL) << 8)
                       |  (bytes[11] & 0xFFL);

            assertEquals(4L, appId, "Credit-Control Application-ID must be 4");
        }

        @Test
        @DisplayName("Hop-by-Hop ID at bytes 12-15")
        void hopByHopIdAtBytes12to15() {
            long hopByHop = 0x12345678L;
            DiameterMessage msg = buildMessageWithHeader(
                AvpCodes.CMD_CREDIT_CONTROL, AvpCodes.APP_CREDIT_CONTROL, hopByHop, 0L
            );
            byte[] bytes = codec.encode(msg);

            long decoded = ((bytes[12] & 0xFFL) << 24)
                         | ((bytes[13] & 0xFFL) << 16)
                         | ((bytes[14] & 0xFFL) << 8)
                         |  (bytes[15] & 0xFFL);

            assertEquals(hopByHop, decoded,
                "Hop-by-Hop ID must be at bytes 12-15");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // GROUPED AVP TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Grouped AVP Tests")
    class GroupedAvpTests {

        @Test
        @DisplayName("Subscription-Id grouped AVP encodes/decodes correctly")
        void subscriptionId_groupedRoundTrip() {
            String msisdn = "919876543210";

            // Build Subscription-Id grouped AVP
            Avp subscriptionIdType = codec.buildUint32Avp(
                AvpCodes.SUBSCRIPTION_ID_TYPE, true, AvpCodes.SUBSCRIPTION_ID_TYPE_E164);
            Avp subscriptionIdData = codec.buildUtf8Avp(
                AvpCodes.SUBSCRIPTION_ID_DATA, true, msisdn);
            Avp grouped = codec.buildGroupedAvp(
                AvpCodes.SUBSCRIPTION_ID, true, subscriptionIdType, subscriptionIdData);

            // Encode in a message
            DiameterMessage msg = buildTestMessage(grouped);
            byte[] bytes = codec.encode(msg);

            // Decode
            DiameterMessage decoded = codec.decode(bytes);
            Avp decodedGrouped = decoded.findAvp(AvpCodes.SUBSCRIPTION_ID).orElseThrow();

            // Read child AVPs
            DiameterMessage children = codec.readGrouped(decodedGrouped);

            Avp typeAvp = children.findAvp(AvpCodes.SUBSCRIPTION_ID_TYPE).orElseThrow();
            Avp dataAvp = children.findAvp(AvpCodes.SUBSCRIPTION_ID_DATA).orElseThrow();

            assertEquals(AvpCodes.SUBSCRIPTION_ID_TYPE_E164, codec.readUint32(typeAvp),
                "Subscription-Id-Type must be E164 (0)");
            assertEquals(msisdn, codec.readUtf8(dataAvp),
                "Subscription-Id-Data must match MSISDN");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FULL MESSAGE TESTS — Complete CCR message
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Full Message Encoding")
    class FullMessageTests {

        @Test
        @DisplayName("Full CCR message encodes and decodes with all required AVPs")
        void fullCcrMessage_roundTrip() {
            String sessionId = "SID;gateway.telecom.com;1717200000;1";
            String msisdn = "919876543210";
            long hopByHop = 12345L;

            // Build CCR message
            DiameterHeader header = DiameterHeader.request(
                AvpCodes.CMD_CREDIT_CONTROL,
                AvpCodes.APP_CREDIT_CONTROL,
                hopByHop, hopByHop
            );
            DiameterMessage ccr = new DiameterMessage(header);
            ccr.addAvp(codec.buildUtf8Avp(AvpCodes.SESSION_ID, true, sessionId));
            ccr.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_HOST, true, "gateway.telecom.com"));
            ccr.addAvp(codec.buildUtf8Avp(AvpCodes.ORIGIN_REALM, true, "telecom.com"));
            ccr.addAvp(codec.buildUtf8Avp(AvpCodes.DESTINATION_REALM, true, "telecom.com"));
            ccr.addAvp(codec.buildUint32Avp(AvpCodes.AUTH_APPLICATION_ID, true, 4L));
            ccr.addAvp(codec.buildUint32Avp(AvpCodes.CC_REQUEST_TYPE, true,
                                             AvpCodes.CC_REQUEST_TYPE_EVENT));
            ccr.addAvp(codec.buildUint32Avp(AvpCodes.CC_REQUEST_NUMBER, true, 0L));
            ccr.addAvp(codec.buildGroupedAvp(AvpCodes.SUBSCRIPTION_ID, true,
                codec.buildUint32Avp(AvpCodes.SUBSCRIPTION_ID_TYPE, true, 0L),
                codec.buildUtf8Avp(AvpCodes.SUBSCRIPTION_ID_DATA, true, msisdn)
            ));

            // Encode
            byte[] bytes = codec.encode(ccr);

            // Verify total size is multiple of 4
            assertEquals(0, bytes.length % 4,
                "Total message length must be multiple of 4 (all AVPs padded)");

            // Decode
            DiameterMessage decoded = codec.decode(bytes);

            // Verify all key fields
            assertEquals(hopByHop, decoded.getHopByHopId(), "Hop-by-Hop ID preserved");
            assertTrue(decoded.isRequest(), "CCR must be a request");
            assertEquals(AvpCodes.CMD_CREDIT_CONTROL, decoded.getCommandCode(),
                "Command code must be 272");

            Avp decodedSessionId = decoded.findAvp(AvpCodes.SESSION_ID).orElseThrow();
            assertEquals(sessionId, codec.readUtf8(decodedSessionId),
                "Session-Id must round-trip correctly");
        }

        @Test
        @DisplayName("Multiple messages with different Hop-by-Hop IDs stay distinct")
        void multipleMessages_distinctHopByHopIds() {
            // Simulates sending 3 concurrent CCRs with different Hop-by-Hop IDs
            long hopByHop1 = 100L;
            long hopByHop2 = 200L;
            long hopByHop3 = 300L;

            byte[] bytes1 = codec.encode(buildRequestMessage(hopByHop1));
            byte[] bytes2 = codec.encode(buildRequestMessage(hopByHop2));
            byte[] bytes3 = codec.encode(buildRequestMessage(hopByHop3));

            DiameterMessage msg1 = codec.decode(bytes1);
            DiameterMessage msg2 = codec.decode(bytes2);
            DiameterMessage msg3 = codec.decode(bytes3);

            // Each decoded message must have its own distinct Hop-by-Hop ID
            assertEquals(hopByHop1, msg1.getHopByHopId());
            assertEquals(hopByHop2, msg2.getHopByHopId());
            assertEquals(hopByHop3, msg3.getHopByHopId());

            assertNotEquals(msg1.getHopByHopId(), msg2.getHopByHopId());
            assertNotEquals(msg2.getHopByHopId(), msg3.getHopByHopId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════

    /** Builds a minimal test message with a fixed header and one AVP */
    private DiameterMessage buildTestMessage(Avp avp) {
        DiameterHeader header = DiameterHeader.request(
            AvpCodes.CMD_CREDIT_CONTROL, AvpCodes.APP_CREDIT_CONTROL, 1L, 1L
        );
        DiameterMessage msg = new DiameterMessage(header);
        msg.addAvp(avp);
        return msg;
    }

    /** Builds a message with a specific header and no AVPs */
    private DiameterMessage buildMessageWithHeader(int commandCode, long appId,
                                                    long hopByHop, long endToEnd) {
        DiameterHeader header = DiameterHeader.request(commandCode, appId, hopByHop, endToEnd);
        return new DiameterMessage(header);
    }

    /** Builds a minimal CCR request with just a Hop-by-Hop ID */
    private DiameterMessage buildRequestMessage(long hopByHop) {
        DiameterHeader header = DiameterHeader.request(
            AvpCodes.CMD_CREDIT_CONTROL, AvpCodes.APP_CREDIT_CONTROL, hopByHop, hopByHop
        );
        DiameterMessage msg = new DiameterMessage(header);
        msg.addAvp(codec.buildUint32Avp(AvpCodes.CC_REQUEST_TYPE, true, 4L));
        return msg;
    }
}
