/**
 * Copyright (c) 2015-present Martin Paljak
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package apdu4j.pcsc;

import apdu4j.core.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

// Construct and parse necessary PC/SC CCID pinpad blocks
public final class PinPadTerminal {
    private static final Logger logger = LoggerFactory.getLogger(PinPadTerminal.class);
    private final Map<FEATURE, Integer> features;
    private boolean display = false;

    // Parse the features into FEATURE -> control code map
    private static Map<FEATURE, Integer> tokenize(byte[] tlv) {
        HashMap<FEATURE, Integer> m = new HashMap<>();

        if (tlv.length % 6 != 0) {
            throw new IllegalArgumentException("Bad response length: " + tlv.length);
        }
        for (int i = 0; i < tlv.length; i += 6) {
            int ft = tlv[i + 0] & 0xFF;
            FEATURE f = FEATURE.fromValue(ft);
            byte[] c = Arrays.copyOfRange(tlv, i + 2, i + 6);
            ByteBuffer buffer = ByteBuffer.wrap(c);
            buffer.order(ByteOrder.BIG_ENDIAN);
            int ci = buffer.getInt();
            m.put(f, ci);
        }
        return m;
    }

    private static void parse_tlv_properties(byte[] tlv) {
        for (int i = 0; i + 2 < tlv.length; ) {
            int t = tlv[i + 0] & 0xFF;
            int l = tlv[i + 1] & 0xFF;
            byte[] v = Arrays.copyOfRange(tlv, i + 2, i + 2 + l);
            i += v.length + 2;
            logger.trace("{}={}", Integer.toHexString(t), HexUtils.bin2hex(v));
        }
    }

    private void parse_pin_properties(byte[] prop) {
        if (prop.length == 4) {
            int cols = prop[0] & 0xFF;
            int rows = prop[1] & 0xFF;
            int pin = prop[2] & 0xFF;
            int timeout = prop[3] & 0xFF;
            if (rows > 0 && cols > 0) {
                display = true;
            }
            logger.debug("cols={} rows={} pin={} timeout={}", cols, rows, pin, timeout);
        } else {
            // XXX: older specification had size of 8
            throw new IllegalArgumentException("Bad PIN properties length: " + prop.length);
        }
    }

    private PinPadTerminal(CardTerminal t, Card c, Map<FEATURE, Integer> features) throws CardException {
        logger.debug("{} has: {}", t.getName(), features.keySet().stream().map(FEATURE::name).collect(Collectors.joining(", ")));
        this.features = new HashMap<>(features);

        // Get PIN properties, if possible
        if (features.containsKey(FEATURE.IFD_PIN_PROPERTIES)) {
            byte[] resp = c.transmitControlCommand(features.get(FEATURE.IFD_PIN_PROPERTIES), new byte[0]);
            if (resp != null && resp.length > 0) {
                parse_pin_properties(resp);
            } else {
                logger.debug("No reply to IOCTL {}", FEATURE.IFD_PIN_PROPERTIES);
            }
        }

        // Get other properties
        if (features.containsKey(FEATURE.GET_TLV_PROPERTIES)) {
            byte[] resp = c.transmitControlCommand(features.get(FEATURE.GET_TLV_PROPERTIES), new byte[0]);
            if (resp != null && resp.length > 0) {
                parse_tlv_properties(resp);
            } else {
                logger.debug("No reply to IOCTL {}", FEATURE.GET_TLV_PROPERTIES);
            }
        }
    }

    public static PinPadTerminal probe(CardTerminal t, Card c) throws CardException {
        final int CM_IOCTL_GET_FEATURE_REQUEST = SCard.CARD_CTL_CODE(3400);

        // Probe for features.
        byte[] resp = c.transmitControlCommand(CM_IOCTL_GET_FEATURE_REQUEST, new byte[]{});
        // Parse features
        Map<FEATURE, Integer> f = tokenize(resp);
        // Try to parse pinpad related features
        return new PinPadTerminal(t, c, f);
    }


    public static String getVMD(CardTerminal t, Card c) {
        try {
            PinPadTerminal ppt = probe(t, c);
            return (ppt.canVerify() ? "V" : " ") + (ppt.canModify() ? "M" : " ") + (ppt.hasDisplay() ? "D" : " ");
        } catch (CardException e) {
            String err = SCard.getExceptionMessage(e);
            if (err.equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                return "???";
            } else
                return "EEE";
        }
    }

    public boolean canVerify() {
        return (features.containsKey(FEATURE.VERIFY_PIN_DIRECT) || (features.containsKey(FEATURE.VERIFY_PIN_START) && features.containsKey(FEATURE.VERIFY_PIN_FINISH)));
    }

    public boolean canModify() {
        return (features.containsKey(FEATURE.MODIFY_PIN_DIRECT) || (features.containsKey(FEATURE.MODIFY_PIN_START) && features.containsKey(FEATURE.MODIFY_PIN_FINISH)));
    }

    public boolean hasDisplay() {
        return display;
    }

    // IOCTL-s of this terminal
    public enum FEATURE {
        VERIFY_PIN_START(0x01),
        VERIFY_PIN_FINISH(0x02),
        MODIFY_PIN_START(0x03),
        MODIFY_PIN_FINISH(0x04),
        GET_KEY_PRESSED(0x05),
        VERIFY_PIN_DIRECT(0x06),
        MODIFY_PIN_DIRECT(0x07),
        MCT_READERDIRECT(0x08),
        MCT_UNIVERSAL(0x09),
        IFD_PIN_PROPERTIES(0x0A),
        ABORT(0x0B),
        SET_SPE_MESSAGE(0x0C),
        VERIFY_PIN_DIRECT_APP_ID(0x0D),
        MODIFY_PIN_DIRECT_APP_ID(0x0E),
        WRITE_DISPLAY(0x0F),
        GET_KEY(0x10),
        IFD_DISPLAY_PROPERTIES(0x11),
        GET_TLV_PROPERTIES(0x12),
        CCID_ESC_COMMAND(0x13),
        EXECUTE_PACE(0x20);

        private final int value;

        FEATURE(int value) {
            this.value = value;
        }

        public static FEATURE fromValue(int v) {
            for (FEATURE f : FEATURE.values()) {
                if (f.value == v)
                    return f;
            }
            return null;
        }

        public int getValue() {
            return value;
        }
    }
}
