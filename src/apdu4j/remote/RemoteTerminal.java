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

import javax.smartcardio.CardTerminal;

import apdu4j.HexUtils;

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
	public String lang = "en"; // XXX: Getter?

	public enum Button {RED, GREEN, YELLOW}

	public RemoteTerminal(JSONMessagePipe pipe) {
		this.pipe = pipe;
		this.terminal = new JSONCardTerminal(pipe);
	}

	public CardTerminal getCardTerminal() {
		return terminal;
	}

	public void start() throws IOException {
		// Read the first START message.
		Map<String, Object> m = pipe.recv();
		if (m.containsKey("cmd") && m.get("cmd").equals("START")) {
			if (m.containsKey("lang") && m.get("lang").toString().matches("\\p{Lower}{2}")) {
				lang = (String) m.get("lang");
			}
			return;
		} else {
			throw new IOException("Invalid START message");
		}

	}
	/**
	 * Shows a message on the screen
	 *
	 * @param text message to be shown
	 * @throws IOException when communication fails
	 */
	public void statusMessage(String text) throws IOException {
		Map<String, Object> m = JSONProtocol.cmd("message");
		m.put("text", text);
		pipe.send(m);
		if (!JSONProtocol.check(m, pipe.recv())) {
			throw new IOException("Could not display status");
		}
	}

	/**
	 * Shows a dialog message to the user and returns the pressed button.
	 *
	 * @param message text to display to the user
	 * @return {@link Button} that was pressed by the user
	 * @throws IOException when communication fails
	 */
	public Button dialog(String message) throws IOException {
		Map<String, Object> m = JSONProtocol.cmd("dialog");
		m.put("text", message);
		pipe.send(m);
		Map<String, Object> r = pipe.recv();
		if (JSONProtocol.check(m, r) || !r.containsKey("button")) {
			throw new IOException("Unknown button pressed");
		}
		return Button.valueOf(((String)r.get("button")).toUpperCase());
	}

	/**
	 * Asks for input from the user.
	 *
	 * @param message text to display to the user
	 * @return null or input
	 * @throws IOException when communication fails
	 */
	public String input(String message) throws IOException {
		Map<String, Object> m = JSONProtocol.cmd("input");
		m.put("text", message);
		pipe.send(m);
		Map<String, Object> r = pipe.recv();
		if (!JSONProtocol.check(m, r) || !r.containsKey("value")) {
			throw new IOException("No value");
		}
		return (String) r.get("value");
	}

	/**
	 * Shows the response of the APDU to the user.
	 *
	 * Normally this requires the verification of a PIN code beforehand.
	 *
	 * @param message text to display to the user
	 * @param apdu APDU to send to the terminal
	 * @return {@link Button} that was pressed by the user
	 * @throws IOException when communication fails
	 */
	public Button decrypt(String message, byte[] apdu) throws IOException {
		Map<String, Object> m = JSONProtocol.cmd("decrypt");
		m.put("text", message);
		m.put("bytes", HexUtils.bin2hex(apdu));
		pipe.send(m);
		Map<String, Object> r = pipe.recv();
		if (JSONProtocol.check(m, r)) {
			return Button.valueOf(((String)r.get("button")).toUpperCase());
		} else {
			throw new IOException("Unknown button pressed");
		}
	}

	/**
	 * Issues a ISO VERIFY on the remote terminal.
	 *
	 * @param p2 P2 parameter in the VERIFY APDU
	 * @param text to be displayed to the user
	 * @return true if VERIFY returned 0x9000, false otherwise
	 * @throws IOException when communication fails
	 */
	public boolean verifyPIN(int p2, String text) throws IOException {
		Map<String, Object> m = JSONProtocol.cmd("verify");
		m.put("p2", p2);
		m.put("text", text);
		pipe.send(m);
		return JSONProtocol.check(m, pipe.recv());
	}

	public void stop(String message){
		try {
			Map<String, Object> m = JSONProtocol.cmd("stop");
			m.put("text", message);
			pipe.send(m);
		} catch (IOException e) {
		}
		close();
	}
	public void close() {
		pipe.close();
	}
}
