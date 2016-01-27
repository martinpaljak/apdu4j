/**
 * Copyright (c) 2015 Martin Paljak
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
package apdu4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;

public class PinPadTerminal implements AutoCloseable {
	private static final int CM_IOCTL_GET_FEATURE_REQUEST = CARD_CTL_CODE(3400);

	private Map<FEATURE, Integer> features = new HashMap<PinPadTerminal.FEATURE, Integer>();
	private boolean display = false;
	private CardTerminal t = null;
	private Card c = null;

	// IOCTL-s of this terminal
	public static enum FEATURE {
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

		private FEATURE(int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}
		public static FEATURE fromValue(int v) {
			for (FEATURE f: FEATURE.values()) {
				if (f.value ==  v)
					return f;
			}
			return null;
		}

	}

	// Parse the features into FEATURE -> control code map
	private static Map<FEATURE, Integer> tokenize(byte [] tlv) {
		HashMap<FEATURE, Integer> m = new HashMap<FEATURE, Integer>();

		if (tlv.length % 6 != 0) {
			throw new IllegalArgumentException("Bad response length: " + tlv.length);
		}
		for (int i = 0; i < tlv.length; i += 6) {
			int ft = tlv [i+0] & 0xFF;
			FEATURE f = FEATURE.fromValue(ft);
			byte[] c = Arrays.copyOfRange(tlv, i+2, i+6);
			ByteBuffer buffer = ByteBuffer.wrap(c);
			buffer.order(ByteOrder.BIG_ENDIAN);
			int ci = buffer.getInt();
			m.put(f, ci);
		}
		return m;
	}

	private  void parse_pin_properties(byte [] prop) {
		if (prop.length == 4) {
			int cols = prop[0] & 0xFF;
			int rows = prop[1] & 0xFF;
			int pin = prop[2] & 0xFF;
			int timeout = prop[3] & 0xFF;
			if (rows > 0 && cols > 0 ) {
				display = true;
			}
			//			System.out.println("COLS: " + cols);
			//			System.out.println("ROWS: " + rows);
			//			System.out.println("PIN : " + pin);
			//			System.out.println("TIME: " + timeout);
		} else {
			// XXX: older specification had size of 8
			throw new IllegalArgumentException("Bad PIN properties length: " + prop.length);
		}
	}

	private static void parse_tlv_properties(byte [] tlv) {
		for (int i = 0; i< tlv.length;) {
			int t = tlv[i+0] & 0xFF;
			int l = tlv[i+1] & 0xFF;
			byte [] v =  Arrays.copyOfRange(tlv, i + 2, i + 2 + l);
			i += v.length + 2;
			System.out.println(Integer.toHexString(t) + "=" + HexUtils.bin2hex(v));
		}
	}
	public static int CARD_CTL_CODE(int c) {
		String os = System.getProperty("os.name", "unknown").toLowerCase();
		if (os.indexOf("windows") != -1) {
			return 0x31 << 16  | c << 2;
		} else {
			return 0x42000000 + c;
		}
	}

	public void probe() throws CardException {
		// probe() only makes sense when applied to terminal
		if (t != null && c == null) {
			c = t.connect("DIRECT");
		}
		try {
			// Probe for features.
			byte [] resp = c.transmitControlCommand(CM_IOCTL_GET_FEATURE_REQUEST, new byte[]{});

			// Parse features
			Map<FEATURE, Integer> props = tokenize(resp);
			features.putAll(props);

			// Get PIN properties, if possible
			if (props.containsKey(FEATURE.IFD_PIN_PROPERTIES)) {
				resp = c.transmitControlCommand(props.get(FEATURE.IFD_PIN_PROPERTIES), new byte[]{});
				if (resp != null && resp.length > 0) {
					parse_pin_properties(resp);
				}
			}

			// Get other properties
			if (props.containsKey(FEATURE.GET_TLV_PROPERTIES)) {
				resp = c.transmitControlCommand(props.get(FEATURE.GET_TLV_PROPERTIES), new byte[]{});
				if (resp != null && resp.length > 0) {
					//parse_tlv_properties(resp);
				}
			}
		} finally {
			c.disconnect(false);
		}
	}

	public PinPadTerminal(CardTerminal terminal) {
		t = terminal;
	}

	public PinPadTerminal(Card card) {
		c = card;
	}

	public boolean canVerify() {
		if (features.containsKey(FEATURE.VERIFY_PIN_DIRECT) || (features.containsKey(FEATURE.VERIFY_PIN_START) && features.containsKey(FEATURE.VERIFY_PIN_FINISH))) {
			return true;
		}
		return false;
	}
	public boolean canModify() {
		if (features.containsKey(FEATURE.MODIFY_PIN_DIRECT) || (features.containsKey(FEATURE.MODIFY_PIN_START) && features.containsKey(FEATURE.MODIFY_PIN_FINISH))) {
			return true;
		}
		return false;
	}
	public boolean hasDisplay() {
		return display;
	}

	@Override
	public void close() throws IOException, CardException {
		if (t != null && c != null) {
			c.disconnect(false); // FIXME: might be true
		}
	}
}
