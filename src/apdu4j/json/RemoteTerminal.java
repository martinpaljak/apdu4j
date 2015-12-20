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
package apdu4j.json;

import java.io.IOException;
import java.util.Map;

import javax.smartcardio.CardTerminal;

/**
 * Implementation of the <a href=
 * "https://github.com/martinpaljak/apdu4j/wiki/JSON-Remote-EMV-Terminal">Remote
 * EMV terminal</a> JSON protocol.
 *
 * @author Martin Paljak
 */
public class RemoteTerminal {
	private final JSONMessagePipe pipe;
	private final CardTerminal terminal;

	public RemoteTerminal(JSONMessagePipe pipe) {
		this.pipe = pipe;
		this.terminal = new JSONCardTerminal(pipe);
	}

	public CardTerminal getCardTerminal() {
		return terminal;
	}

	/**
	 * Shows a message on the screen
	 *
	 * @param text message to be shown
	 * @throws IOException
	 */
	public void statusMessage(String text) throws IOException {
		Map<String, Object> m = JSONProtocol.cmd("message");
		m.put("text", text);
		pipe.send(m);
		JSONProtocol.check(m, pipe.recv(), null, null);
	}

	// Shows a dialog message to the user. Returns true if user presses OK, false otherwise
	public boolean dialog(String message) throws IOException {
		Map<String, Object> m = JSONProtocol.cmd("dialog");
		m.put("text", message);
		pipe.send(m);
		return JSONProtocol.check(m, pipe.recv(), "button", "green");
	}

	// Verify a PIN (possibly with a pinpad and return the verification response.
	public boolean verifyPIN(int p2, String text) throws IOException {
		Map<String, Object> m = JSONProtocol.cmd("verify");
		m.put("p2", p2);
		m.put("text", text);
		pipe.send(m);
		return JSONProtocol.check(m, pipe.recv(), null, null);
	}
}
