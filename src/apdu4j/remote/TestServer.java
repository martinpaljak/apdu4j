/*
 * Copyright (c) 2014-2015 Martin Paljak
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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import apdu4j.HexUtils;
import apdu4j.remote.RemoteTerminal.Button;

// Sample test server for remote terminal.
public class TestServer extends RemoteTerminalThread {
	private static Logger logger = LoggerFactory.getLogger(TestServer.class);

	// Start a socket test server.
	static void start(ServerSocket socket) {
		ExecutorService executor = Executors.newWorkStealingPool();
		while(true) {
			try {
				Socket s = socket.accept();
				SocketTransport transport = new SocketTransport(s);
				TestServer client = new TestServer();
				client.setTerminal(new RemoteTerminal(transport));
				executor.execute(client);
			} catch (IOException e) {
				logger.trace("Could not accept client", e);
			}
		}
	}

	private void setTerminal(RemoteTerminal t) {
		terminal = t;
	}

	// Run a sample session for a client.
	@Override
	public void run() {
		logger.info("Started session");
		try {
			terminal.start();
			terminal.statusMessage("Welcome!");
			try {

				CardTerminal ct = terminal.getCardTerminal();
				CardChannel c = ct.connect("*").getBasicChannel();

				if (terminal.dialog("Shall we try to select MF in " + ct.getName()).equals(Button.GREEN)) {
					ResponseAPDU r = c.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00));
					terminal.statusMessage("Card returned: " + HexUtils.encodeHexString(r.getBytes()));
				}
			} catch (CardException e) {
				terminal.statusMessage("Failed: " + e.getMessage());
			} finally {
				terminal.stop();
			}
		}
		catch (IOException e) {
			logger.error("Communication error", e);
		}
	}
}
