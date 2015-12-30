/*
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
package apdu4j.remote;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import javax.smartcardio.ATR;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import apdu4j.HexUtils;

/**
 * Implements the {@link CardTerminal} portion of a {@link RemoteTerminal}
 *
 * @author Martin Paljak
 */

class JSONCardTerminal extends CardTerminal {
	// Connection properties
	private String terminalName = null;
	private ATR atr = null;
	private String protocol = null;

	private final JSONMessagePipe client;


	public JSONCardTerminal(JSONMessagePipe c) {
		// client is used for sending the actual JSON messages.
		client = c;
	}

	@Override
	public Card connect(String protocol) throws CardException {
		try {
			HashMap<String, Object> m = new HashMap<>();
			m.put("cmd", "CONNECT");
			m.put("protocol", protocol);
			client.send(m);

			// Read back the response
			Map<String, Object> r = client.recv();
			if (r.containsKey("CONNECT") && ((String)r.get("CONNECT")).equals("OK")) {

				if (r.containsKey("atr") && r.containsKey("protocol") && r.containsKey("reader")) {
					terminalName = (String) r.get("reader");
					atr = new ATR(HexUtils.stringToBin((String) r.get("atr")));
					protocol = ((String) r.get("protocol"));
					return new JSONCard(this);
				}
			}
			throw new CardException("Could not connect to client");
		} catch (IOException e) {
			throw new CardException("Could not connect to client", e);
		}

	}
	@Override
	public String getName() {
		if (terminalName == null) {
			return "RemoteTerminal (not connected)";
		}
		else {
			return "RemoteTerminal (" + terminalName + ")";
		}
	}

	@Override
	public boolean isCardPresent() throws CardException {
		// We always assume a present card and fail if actual connection fails.
		// FIXME: assume a connectd card.
		return true;
	}

	@Override
	public boolean waitForCardAbsent(long arg0) throws CardException {
		throw new CardException("JSONCardTerminal does not support polling for card removal");
	}

	@Override
	public boolean waitForCardPresent(long arg0) throws CardException {
		// FIXME: implement waiting
		throw new CardException("JSONCardTerminal does not support polling");
	}

	private class JSONCard extends Card {
		private final JSONCardTerminal terminal;
		private JSONCardChannel channel = null;

		protected JSONCard(JSONCardTerminal t) {
			terminal = t;
		}

		@Override
		public void beginExclusive() throws CardException {
			// Do nothing, exclusiveness is expected to be maintained by the client
		}

		@Override
		public void disconnect(boolean reset) throws CardException {
			try {
				HashMap<String, Object> m = new HashMap<>();
				m.put("cmd", "DISCONNECT");
				m.put("action", reset ? "reset" : "leave");
				terminal.client.send(m);
				// Read back FIXME throw card exception if something fails?
				terminal.client.recv();
			} catch (IOException e) {
				throw new CardException("Disconnect failed", e);
			}
		}

		@Override
		public void endExclusive() throws CardException {
			// Do nothing, exclusiveness is expected to be maintained by the client
		}

		@Override
		public ATR getATR() {
			return atr;
		}

		@Override
		public synchronized CardChannel getBasicChannel() {
			if (channel == null)
				channel = new JSONCardChannel(this);
			return channel;
		}

		@Override
		public String getProtocol() {
			return protocol;
		}

		@Override
		public CardChannel openLogicalChannel() throws CardException {
			throw new CardException("JSONCard does not support logical channels");
		}

		@Override
		public byte[] transmitControlCommand(int arg0, byte[] arg1) throws CardException {
			throw new CardException("JSONCard does not yet support control commands");
		}

		protected class JSONCardChannel extends CardChannel {
			private final JSONCard card;
			public JSONCardChannel(JSONCard c) {
				card = c;
			}

			@Override
			public void close() throws CardException {
				// Do nothing, this is the basic channel
			}

			@Override
			public Card getCard() {
				return card;
			}

			@Override
			public int getChannelNumber() {
				// We only support the basic channel.
				return 0;
			}

			@Override
			public ResponseAPDU transmit(CommandAPDU apdu) throws CardException {
				try {
					String apdustring = HexUtils.encodeHexString(apdu.getBytes());


					Map<String, Object> m = JSONProtocol.cmd("APDU");
					m.put("bytes", apdustring);
					client.send(m);

					Map<String, Object> r = client.recv();

					if (!JSONProtocol.check(m, r, null, null)) {
						throw new CardException((String)r.get("ERROR"));
					}

					return new ResponseAPDU(HexUtils.stringToBin((String)r.get("bytes")));
				} catch (IOException e) {
					throw new CardException(e);
				}
			}

			@Override
			public int transmit(ByteBuffer arg0, ByteBuffer arg1) throws CardException {
				throw new CardException("JSONCardChannel only supports APDU based interface");
			}

		}
	}
}
