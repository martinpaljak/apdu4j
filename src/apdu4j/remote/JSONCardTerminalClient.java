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
import java.util.Map;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import apdu4j.HexUtils;
import apdu4j.TerminalManager;

// Given a native CardTerminal and a JSONMessagePipe, connects the reader
// to the other side of the pipe with the JSON protocol
class JSONCardTerminalClient {
	private final CardTerminal terminal;
	private final JSONMessagePipe pipe;
	protected Card card = null; // There can be several connects-disconnects
	private String protocol = null; // Local protocol to use, overriding the other side

	public JSONCardTerminalClient(CardTerminal terminal, JSONMessagePipe pipe) {
		this.terminal = terminal;
		this.pipe = pipe;
	}

	public void forceProtocol(String protocol) {
		this.protocol = protocol;
	}
	public boolean processMessage(Map<String, Object> msg) throws IOException, CardException {
		if (!msg.containsKey("cmd"))
			throw new IOException("No command field in message: " + msg.toString());
		String cmd = (String) msg.get("cmd");
		if (cmd.equals("CONNECT")) {
			try {
				if (protocol == null && msg.containsKey("protocol")) {
					protocol = (String) msg.get("protocol");
				}
				card = terminal.connect(protocol);
				card.beginExclusive();
				Map<String, Object> m = JSONProtocol.ok(msg);
				m.put("atr", HexUtils.encodeHexString(card.getATR().getBytes()));
				m.put("reader", terminal.getName());
				m.put("protocol", card.getProtocol());
				pipe.send(m);
			} catch (CardException e) {
				fail(msg, e);
			}
		} else if (cmd.equals("APDU")) {
			try {
				CommandAPDU command = new CommandAPDU(HexUtils.stringToBin((String) msg.get("bytes")));
				ResponseAPDU r = card.getBasicChannel().transmit(command);
				Map<String, Object> m = JSONProtocol.ok(msg);
				m.put("bytes", HexUtils.encodeHexString(r.getBytes()));
				pipe.send(m);
			}
			catch (CardException e) {
				fail(msg, e);
			}
		} else if (cmd.equals("DISCONNECT")) {
			try {
				card.endExclusive();
				card.disconnect(true);
				pipe.send(JSONProtocol.ok(msg));
			}
			catch (CardException e) {
				fail(msg, e);
			}
		} else {
			return false;
		}
		return true;
	}

	public boolean isConnected() {
		return card != null;
	}

	private void fail(Map<String, Object> msg, Exception e) throws IOException {
		pipe.send(JSONProtocol.nok(msg, TerminalManager.getExceptionMessage(e)));
	}
}
