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

import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import apdu4j.HexUtils;
import apdu4j.TerminalManager;

/**
 * Client side implementation of the remote EMV terminal protocol that works
 * from the command line.
 *
 * @author Martin Paljak
 */
public class CmdlineRemoteTerminal implements Runnable {
	private JSONMessagePipe pipe;
	// The terminal that is tunneled
	private JSONCardTerminalClient jsonterminal;

	public CmdlineRemoteTerminal(JSONMessagePipe pipe, CardTerminal terminal) {
		this.pipe = pipe;
		this.jsonterminal = new JSONCardTerminalClient(terminal, pipe);
	}

	@Override
	public void run() {
		try {
			// Initiate communication.
			pipe.send(JSONProtocol.cmd("start"));
			while (true) {
				// Read a message
				Map<String, Object> m = pipe.recv();
				// First try the terminal
				if (!jsonterminal.processMessage(m)) {
					if (!processMessage(m))
						break;
				}
			}
		} catch (IOException e) {
			System.out.println("Messaging failed: " + e.getMessage());
		} catch (CardException e) {
			System.out.println("\nReader failed: " + TerminalManager.getExceptionMessage(e));
		}
	}

	public void forceProtocol(String protocol) {
		jsonterminal.forceProtocol(protocol);
	}
	private void verify(Map<String, Object> msg) throws IOException {
		if (!jsonterminal.isConnected()) {
			throw new IllegalStateException("Can not verify PIN codes if no connection to a card is established!");
		}
		Console c = System.console();
		int p2 = ((Long)msg.get("p2")).intValue();
		System.out.println((String)msg.get("text"));

		char[] input = c.readPassword("Enter PIN: ");
		if (input == null) {
			pipe.send(JSONProtocol.nok(msg, "No pin entered"));
			return;
		}

		byte[] pin = new String(input).getBytes(StandardCharsets.UTF_8);
		CommandAPDU verify = new CommandAPDU(0x00, 0x20, 0x00, p2, pin);
		try {
			ResponseAPDU r = jsonterminal.card.getBasicChannel().transmit(verify);
			Map< String, Object> m = null;
			if (r.getSW() == 0x9000) {
				m = JSONProtocol.ok(msg);
			} else {
				m = JSONProtocol.nok(msg, "Verification failed");
				m.put("bytes", HexUtils.encodeHexString(r.getBytes()));
			}
			pipe.send(m);
		} catch (CardException e) {
			pipe.send(JSONProtocol.nok(msg, e.getMessage()));
		}
	}

	private void dialog(Map<String, Object> msg) throws IOException {
		System.out.println("# " + msg.get("text"));
		Map< String, Object> m = JSONProtocol.ok(msg);
		boolean yes = false;

		// lanterna requires work as it screws up Console.readPassword()
		yes = get_yes_or_no_console();

		if (!yes) {
			m.put("button", "red");
		} else {
			m.put("button", "green");
		}
		pipe.send(m);
	}

	private void decrypt(Map<String, Object> msg) throws IOException {
		String cmd = (String) msg.get("bytes");
		if (cmd == null)
			throw new IOException("bytes is null");

		CommandAPDU c = new CommandAPDU(HexUtils.decodeHexString(cmd));

		try {
			ResponseAPDU r = jsonterminal.card.getBasicChannel().transmit(c);
			if (r.getSW() == 0x9000) {
				System.out.println("# " + msg.get("text"));
				System.out.println("# " + new String(r.getData(), "UTF-8"));
				Map< String, Object> m = JSONProtocol.ok(msg);
				boolean yes = false;
				// lanterna requires work as it screws up Console.readPassword()
				yes = get_yes_or_no_console();

				if (!yes) {
					m.put("button", "red");
				} else {
					m.put("button", "green");
				}
				pipe.send(m);
			} else {
				Map<String, Object> rm = JSONProtocol.nok(msg, "Card returned " + r.getSW());
				rm.put("bytes", Arrays.copyOfRange(r.getBytes(), r.getBytes().length - 2, r.getBytes().length));
				pipe.send(rm);
			}
		} catch (CardException e) {
			pipe.send(JSONProtocol.nok(msg, e.getMessage()));
		}
	}


	private void message(Map<String, Object> msg) throws IOException {
		System.out.println("# " + (String)msg.get("text"));
		pipe.send(JSONProtocol.ok(msg));
	}

	private void stop(Map<String, Object> msg) {
		String text = "# Connection closed";
		if (msg.containsKey("text")) {
			text = text + ": " + msg.get("text");
		} else {
			text = text + ".";
		}
		System.out.println(text);
	}

	private boolean processMessage (Map<String, Object> msg) throws IOException {
		if (!msg.containsKey("cmd")) {
			throw new IOException("No command in message: " + msg);
		}
		String cmd = (String) msg.get("cmd");
		if (cmd.equals("MESSAGE")) {
			message(msg);
		} else if (cmd.equals("VERIFY")) {
			verify(msg);
		} else if (cmd.equals("DIALOG")) {
			dialog(msg);
		} else if (cmd.equals("STOP")) {
			stop(msg);
			return false;
		} else if (cmd.equals("DECRYPT")) {
			decrypt(msg);
		} else {
			System.err.println("No idea how to process: " + msg.toString());
			return false;
		}
		return true;
	}

	private boolean get_yes_or_no_console() {
		Console c = System.console();
		while (true) {
			String response = c.readLine("y/n ? ");
			if (response == null)
				continue;
			response = response.trim();
			if (response.equalsIgnoreCase("y")) {
				return true;
			} else if (response.equalsIgnoreCase("n")) {
				return false;
			} else {
				System.out.println("Please enter 'y' or 'n' followed by ENTER");
			}
		}
	}

}
