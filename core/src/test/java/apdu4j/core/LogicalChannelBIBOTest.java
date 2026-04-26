// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class LogicalChannelBIBOTest {

    // --- CLA encoding ---

    @Test
    void testChannel0PassesThrough() {
        // Channel 0: CLA unchanged, early return in transceive() - encodeChannel() not called
        var mock = MockBIBO.with("00A40400", "9000");
        var ch = new LogicalChannelBIBO(mock, 0);
        ch.transceive(HexUtils.hex2bin("00A40400"));
    }

    @Test
    void testBasicChannelCLAEncoding() {
        // Channels 1-3: (cla & 0xBC) | channel
        for (var e : new int[][]{{1, 0x01}, {3, 0x03}}) {
            var mock = MockBIBO.with("%02XA40400".formatted(e[1]), "9000");
            var ch = new LogicalChannelBIBO(mock, e[0]);
            ch.transceive(HexUtils.hex2bin("00A40400"));
        }
    }

    @Test
    void testExtendedChannelCLAEncoding() {
        // Channels 4-19: (cla & 0xB0) | 0x40 | (channel - 4)
        for (var e : new int[][]{{4, 0x40}, {5, 0x41}, {19, 0x4F}}) {
            var mock = MockBIBO.with("%02XA40400".formatted(e[1]), "9000");
            var ch = new LogicalChannelBIBO(mock, e[0]);
            ch.transceive(HexUtils.hex2bin("00A40400"));
        }
    }

    @Test
    void testProprietaryCLAUntouched() {
        // Proprietary CLA (bit 7 set): not modified
        var mock = MockBIBO.with("80CA0000", "9000");
        var ch = new LogicalChannelBIBO(mock, 5);
        ch.transceive(HexUtils.hex2bin("80CA0000"));
    }

    @Test
    void testCLAPreservesSecureMessagingBits() {
        // CLA=0x0C (secure messaging) + channel 2 -> (0x0C & 0xBC) | 2 = 0x0E
        var mock = MockBIBO.with("0EA40400", "9000");
        var ch = new LogicalChannelBIBO(mock, 2);
        ch.transceive(HexUtils.hex2bin("0CA40400"));
    }

    // --- open/close lifecycle ---

    @Test
    void testOpenCloseLifecycle() {
        // MANAGE CHANNEL OPEN returns channel 1, transceive encodes CLA, close sends MANAGE CHANNEL CLOSE
        var mock = MockBIBO.with("0070000001", "019000")
                .then("01A40400", "9000")
                .then("01708001", "9000");
        var ch = LogicalChannelBIBO.open(mock);
        assertEquals(ch.getChannel(), 1);
        ch.transceive(HexUtils.hex2bin("00A40400"));
        ch.close();
    }

    @Test
    void testChannel0CloseIsNoop() {
        // Channel 0 close does not send any command
        var mock = MockBIBO.of();
        var ch = new LogicalChannelBIBO(mock, 0);
        ch.close(); // no command sent, no depleted exception
    }

    @Test
    void testCloseIsIdempotent() {
        var mock = MockBIBO.with("0070000001", "019000")
                .then("01708001", "9000");
        var ch = LogicalChannelBIBO.open(mock);
        ch.close();
        ch.close(); // second close is no-op
    }

    // --- error conditions ---

    @Test
    void testErrorConditions() {
        // Invalid channel number
        assertThrows(IllegalArgumentException.class, () -> new LogicalChannelBIBO(MockBIBO.of(), 20));

        // MANAGE CHANNEL OPEN with bad SW
        assertThrows(BIBOException.class, () -> LogicalChannelBIBO.open(MockBIBO.of("6A81")));

        // Transceive after close
        var mock = MockBIBO.with("0070000001", "019000").then("01708001", "9000");
        var ch = LogicalChannelBIBO.open(mock);
        ch.close();
        assertThrows(BIBOException.class, () -> ch.transceive(HexUtils.hex2bin("00A40400")));
    }

    // --- composability ---

    @Test
    void testComposableWithGetResponseWrapper() {
        // Open channel 1, wrap with GetResponseWrapper, verify chaining uses channel CLA
        var mock = MockBIBO.with("0070000001", "019000")
                .then("01A40400", "AA6102")
                .then("01C0000002", "BBCC9000");
        var ch = LogicalChannelBIBO.open(mock)
                .then(GetResponseWrapper::wrap);
        var result = ch.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(result, HexUtils.hex2bin("AABBCC9000"));
    }
}
