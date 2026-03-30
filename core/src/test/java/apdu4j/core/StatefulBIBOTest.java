/*
 * Copyright (c) 2026-present Martin Paljak <martin@martinpaljak.net>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package apdu4j.core;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class StatefulBIBOTest {

    // Identity wrap/unwrap - passes through unchanged
    static final StatefulBIBO.Wrap<Integer> ID_WRAP = (cmd, s) -> new Stateful<>(cmd, s);
    static final StatefulBIBO.Unwrap<Integer> ID_UNWRAP = (resp, s) -> new Stateful<>(resp, s);

    // --- state threading ---

    @Test
    void testStateThreading() {
        // Asymmetric increments: wrap +1, unwrap +10. After N cycles state == N*11
        var mock = MockBIBO.of("9000", "9000", "9000");
        StatefulBIBO.Wrap<Integer> wrap = (cmd, s) -> new Stateful<>(cmd, s + 1);
        StatefulBIBO.Unwrap<Integer> unwrap = (resp, s) -> new Stateful<>(resp, s + 10);
        var bibo = new StatefulBIBO<>(mock, 0, wrap, unwrap);

        // Initial state accessible before any transceive
        assertEquals(bibo.state(), Integer.valueOf(0));

        bibo.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(bibo.state(), Integer.valueOf(11));

        bibo.transceive(HexUtils.hex2bin("00A40400"));
        bibo.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(bibo.state(), Integer.valueOf(33));

        // Verify ordering: wrap runs before unwrap within each cycle
        var mock2 = MockBIBO.of("9000");
        StatefulBIBO.Wrap<List<String>> wLog = (cmd, s) -> {
            var copy = new ArrayList<>(s);
            copy.add("W");
            return new Stateful<>(cmd, copy);
        };
        StatefulBIBO.Unwrap<List<String>> uLog = (resp, s) -> {
            var copy = new ArrayList<>(s);
            copy.add("U");
            return new Stateful<>(resp, copy);
        };
        var logBibo = new StatefulBIBO<>(mock2, List.<String>of(), wLog, uLog);
        logBibo.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(logBibo.state(), List.of("W", "U"));
    }

    // --- command/response transformation ---

    @Test
    void testTransformation() {
        // Wrap: set CLA to 0x84 (secure messaging indicator)
        // Unwrap: strip last 8 bytes from response data (simulated MAC removal)
        var mock = MockBIBO.with("84A4040007A000000062810100", "AABB" + "0102030405060708" + "9000");
        StatefulBIBO.Wrap<Integer> wrap = (cmd, s) -> {
            var modified = new CommandAPDU(0x84, cmd.getINS(), cmd.getP1(), cmd.getP2(), cmd.getData(), cmd.getNe());
            return new Stateful<>(modified, s);
        };
        StatefulBIBO.Unwrap<Integer> unwrap = (resp, s) -> {
            var data = resp.getData();
            // strip 8-byte trailing MAC, keep SW
            var stripped = Arrays.copyOf(data, data.length - 8);
            var result = new ResponseAPDU(HexBytes.concatenate(stripped, resp.getSWBytes()));
            return new Stateful<>(result, s);
        };
        var bibo = new StatefulBIBO<>(mock, 0, wrap, unwrap);
        var result = bibo.transceive(HexUtils.hex2bin("00A4040007A000000062810100"));

        // Caller sees stripped response (MAC removed)
        assertEquals(result, HexUtils.hex2bin("AABB9000"));

        // Identity passthrough: command and response unchanged
        var mock2 = MockBIBO.with("00CA0000", "DEADBEEF9000");
        var passthrough = new StatefulBIBO<>(mock2, 0, ID_WRAP, ID_UNWRAP);
        assertEquals(passthrough.transceive(HexUtils.hex2bin("00CA0000")), HexUtils.hex2bin("DEADBEEF9000"));
    }

    // --- close and cleanup ---

    @Test
    void testCloseAndCleanup() {
        // Non-closeable state: close() is a no-op, underlying BIBO stays open
        var mock = MockBIBO.of("9000");
        var bibo = new StatefulBIBO<>(mock, 0, ID_WRAP, ID_UNWRAP);
        bibo.close();
        // Underlying BIBO is NOT closed - session doesn't own the transport
        mock.transceive(HexUtils.hex2bin("00A40400"));

        // AutoCloseable state: close() calls state.close()
        var key = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        record KeyState(byte[] key) implements AutoCloseable {
            @Override
            public void close() {
                Arrays.fill(key, (byte) 0);
            }
        }
        StatefulBIBO.Wrap<KeyState> kw = (cmd, s) -> new Stateful<>(cmd, s);
        StatefulBIBO.Unwrap<KeyState> ku = (resp, s) -> new Stateful<>(resp, s);
        var mock2 = MockBIBO.of();
        var bibo2 = new StatefulBIBO<>(mock2, new KeyState(key), kw, ku);
        bibo2.close();
        assertEquals(key, new byte[8]); // zeroed via state.close()
    }

    // --- error propagation ---

    @Test
    void testErrorPropagation() {
        // BIBOException from transport: state unchanged (wrap ran but cycle didn't complete)
        StatefulBIBO.Wrap<Integer> countWrap = (cmd, s) -> new Stateful<>(cmd, s + 1);
        StatefulBIBO.Unwrap<Integer> countUnwrap = (resp, s) -> new Stateful<>(resp, s + 10);
        var mock = MockBIBO.throwing();
        var bibo = new StatefulBIBO<>(mock, 0, countWrap, countUnwrap);
        assertThrows(BIBOException.class, () -> bibo.transceive(HexUtils.hex2bin("00A40400")));
        assertEquals(bibo.state(), Integer.valueOf(0)); // pre-cycle value preserved

        // RuntimeException from wrap: state unchanged (wrap didn't complete)
        StatefulBIBO.Wrap<Integer> badWrap = (cmd, s) -> {
            throw new IllegalStateException("wrap failed");
        };
        var mock2 = MockBIBO.of("9000");
        var bibo2 = new StatefulBIBO<>(mock2, 0, badWrap, countUnwrap);
        assertThrows(IllegalStateException.class, () -> bibo2.transceive(HexUtils.hex2bin("00A40400")));
        assertEquals(bibo2.state(), Integer.valueOf(0));

        // RuntimeException from unwrap: state unchanged (cycle didn't complete)
        StatefulBIBO.Unwrap<Integer> badUnwrap = (resp, s) -> {
            throw new IllegalStateException("unwrap failed");
        };
        var mock3 = MockBIBO.of("9000");
        var bibo3 = new StatefulBIBO<>(mock3, 0, countWrap, badUnwrap);
        assertThrows(IllegalStateException.class, () -> bibo3.transceive(HexUtils.hex2bin("00A40400")));
        assertEquals(bibo3.state(), Integer.valueOf(0));
    }

    // --- composability ---

    @Test
    void testComposability() {
        // StatefulBIBO + GetResponseWrapper: GET RESPONSE chaining goes through
        // StatefulBIBO's transceive, so state evolves for each round
        var mock = MockBIBO.of("AA6102", "BBCC9000");
        StatefulBIBO.Wrap<Integer> wrap = (cmd, s) -> new Stateful<>(cmd, s + 1);
        StatefulBIBO.Unwrap<Integer> unwrap = (resp, s) -> new Stateful<>(resp, s + 1);
        var stateful = new StatefulBIBO<>(mock, 0, wrap, unwrap);
        var bibo = stateful.then(GetResponseWrapper::wrap);

        var result = bibo.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(result, HexUtils.hex2bin("AABBCC9000"));
        // 2 transceives through StatefulBIBO (original + GET RESPONSE), each does wrap+unwrap
        assertEquals(stateful.state(), Integer.valueOf(4));
    }

    // --- realistic secure messaging simulation ---

    @Test
    void testSecureMessagingSimulation() {
        // Simulates a 3-command SCP-like session:
        // - State: command counter + session key (AutoCloseable for key zeroing)
        // - Wrap: appends a 4-byte "MAC" derived from counter, sets CLA=0x84
        // - Unwrap: verifies response SW
        // - Close: zeros the session key via state.close()

        var sessionKey = new byte[]{0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48};

        record SCPState(int counter, byte[] key) implements AutoCloseable {
            @Override
            public void close() {
                Arrays.fill(key, (byte) 0);
            }
        }

        // Wrapped commands: CLA=84, original data + 4-byte counter MAC appended
        var mock = MockBIBO
                .with("84A404000BA000000062810100000001", "AABB9000")
                .then("84CA00000400000002", "CCDD9000")
                .then("84CA00000400000003", "EEFF9000");

        StatefulBIBO.Wrap<SCPState> wrap = (cmd, s) -> {
            var counter = s.counter() + 1;
            var mac = new byte[]{0, 0, (byte) (counter >> 8), (byte) counter};
            var newData = HexBytes.concatenate(cmd.getData(), mac);
            var wrapped = new CommandAPDU(0x84, cmd.getINS(), cmd.getP1(), cmd.getP2(), newData);
            return new Stateful<>(wrapped, new SCPState(counter, s.key()));
        };

        StatefulBIBO.Unwrap<SCPState> unwrap = (resp, s) -> {
            if (resp.getSW() != 0x9000) {
                throw new BIBOException("Unexpected SW: %04X".formatted(resp.getSW()));
            }
            return new Stateful<>(resp, s);
        };

        try (var bibo = new StatefulBIBO<>(mock, new SCPState(0, sessionKey), wrap, unwrap)) {
            var r1 = bibo.transceive(HexUtils.hex2bin("00A4040007A0000000628101"));
            assertEquals(HexUtils.bin2hex(r1), "AABB9000");
            assertEquals(bibo.state().counter(), 1);

            bibo.transceive(HexUtils.hex2bin("00CA0000"));
            bibo.transceive(HexUtils.hex2bin("00CA0000"));
            assertEquals(bibo.state().counter(), 3);
        }
        // Key zeroed by try-with-resources via SCPState.close()
        assertEquals(sessionKey, new byte[8]);
    }
}
