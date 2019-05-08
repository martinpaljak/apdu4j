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
import java.util.Locale;
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
	JSONCardTerminalClient jsonterminal;

	public CmdlineRemoteTerminal(JSONMessagePipe pipe, CardTerminal terminal) {
		this.pipe = pipe;
		this.jsonterminal = new JSONCardTerminalClient(terminal, pipe);
	}

	@Override
	public void run() {
		try {
			// Initiate communication.
			Map<String, Object> st = JSONProtocol.cmd("start");
			st.put("lang", Locale.getDefault().getLanguage());
			pipe.send(st);
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
		jsonterminal.protocol = protocol;
	}
	public void transact(boolean yes) {
		jsonterminal.transact = yes;
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
				m.put("bytes", HexUtils.bin2hex(r.getBytes()));
			}
			pipe.send(m);
		} catch (CardException e) {
			pipe.send(JSONProtocol.nok(msg, e.getMessage()));
		}
	}

	private void dialog(Map<String, Object> msg) throws IOException {
		System.out.println("# " + msg.get("text"));
		Map< String, Object> m = JSONProtocol.ok(msg);
		boolean yes = get_yes_or_no_console("Decision");

		if (!yes) {
			m.put("button", "red");
		} else {
			m.put("button", "green");
		}
		pipe.send(m);
	}

	private void input(Map<String, Object> msg) throws IOException {
		System.out.println("# " + msg.get("text"));
		Map< String, Object> m = JSONProtocol.ok(msg);

		Console c = System.console();
		String input = c.readLine("> ");
		if (input == null) {
			Map< String, Object> nack = JSONProtocol.nok(msg, "Input was null");
			pipe.send(nack);
			return;
		}
		input = input.trim();
		System.out.println("> \""+ input + "\"");
		boolean yes = get_yes_or_no_console("Confirm");
		if (!yes) {
			m.put("button", "red");
		} else {
			m.put("value", input);
			m.put("button", "green");
		}
		pipe.send(m);
	}

	private void select(Map<String, Object> msg) throws IOException {
		System.out.println("# " + msg.get("text"));
		while (true) {
			// Show options.
			for (int i = 1; i<=5; i++ ) {
				if (msg.containsKey(Integer.toString(i))) {
					System.out.println("> " + i + "=" + msg.get(Integer.toString(i)));
				} else {
					break;
				}
			}
			// Read choice
			Console c = System.console();
			String input = c.readLine(msg.get("text") + " > ");
			if (input == null) {
				Map< String, Object> nack = JSONProtocol.nok(msg, "Input was null");
				pipe.send(nack);
				return;
			}
			// Validate choice
			input = input.trim();
			int choice;
			try {
				choice = Integer.parseInt(input);
			} catch (NumberFormatException e) {
				System.err.println("\""+input+"\" is not a number");
				continue;
			}
			if (!msg.containsKey(input)) {
				System.err.println("\""+input+"\" is not an available option");
				continue;
			}
			System.out.println("> \""+ msg.get(Integer.toString(choice)) + "\"");
			boolean yes = get_yes_or_no_console("Confirm");
			Map< String, Object> m = JSONProtocol.ok(msg);
			if (!yes) {
				m.put("button", "red");
			} else {
				m.put("value", input);
				m.put("button", "green");
			}
			pipe.send(m);
			break;
		}
	}


	private void decrypt(Map<String, Object> msg) throws IOException {
		String cmd = (String) msg.get("bytes");
		if (cmd == null)
			throw new IOException("bytes is null");

		CommandAPDU c = new CommandAPDU(HexUtils.hex2bin(cmd));

		try {
			ResponseAPDU r = jsonterminal.card.getBasicChannel().transmit(c);
			if (r.getSW() == 0x9000) {
				System.out.println("# " + msg.get("text"));
				System.out.println("# " + new String(r.getData(), "UTF-8"));
				Map< String, Object> m = JSONProtocol.ok(msg);
				boolean yes = get_yes_or_no_console("Confirm");

				if (!yes) {
					m.put("button", "red");
				} else {
					m.put("button", "green");
				}
				pipe.send(m);
			} else {
				Map<String, Object> rm = JSONProtocol.nok(msg, "Card returned 0x" + Integer.toHexString(r.getSW()));
				rm.put("bytes", HexUtils.bin2hex(Arrays.copyOfRange(r.getBytes(), r.getBytes().length - 2, r.getBytes().length)));
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
		} else if (cmd.equals("INPUT")) {
			input(msg);
		} else if (cmd.equals("SELECT")) {
			select(msg);
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

	private boolean get_yes_or_no_console(String msg) {
		Console c = System.console();
		while (true) {
			String response = c.readLine(msg + " y/n ? ");
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
