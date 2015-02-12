/**
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
package apdu4j;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;

import javax.smartcardio.ATR;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

// TODO: Unify the APDU related code with ByteBuffer so that both would log nicely
public class LoggingCardTerminal extends CardTerminal {

	// The actual terminal
	protected CardTerminal terminal = null;
	protected PrintStream dump = null;
	protected PrintStream log = null;

	public static LoggingCardTerminal getInstance(CardTerminal term) {
		return new LoggingCardTerminal(term, System.out, null);
	}

	public static LoggingCardTerminal getInstance(CardTerminal term, OutputStream dump) {
		return new LoggingCardTerminal(term, System.out, dump);
	}

	private LoggingCardTerminal (CardTerminal term, OutputStream log, OutputStream dump) {
		this.terminal = term;
		this.log = new PrintStream(log, true);
		if (dump != null) {
			this.dump = new PrintStream(dump, true);
		}
	}

	@Override
	public Card connect(String arg0) throws CardException {
		return new LoggingCard(terminal, arg0);
	}

	@Override
	public String getName() {
		return terminal.getName();
	}

	@Override
	public boolean isCardPresent() throws CardException {
		return terminal.isCardPresent();
	}

	@Override
	public boolean waitForCardAbsent(long arg0) throws CardException {
		return terminal.waitForCardAbsent(arg0);

	}

	@Override
	public boolean waitForCardPresent(long arg0) throws CardException {
		return terminal.waitForCardPresent(arg0);
	}


	public final class LoggingCard extends Card {
		private final Card card;
		private LoggingCard(CardTerminal term, String protocol) throws CardException {
			log.print("SCardConnect(\"" + terminal.getName() + "\", " + (protocol.equals("*") ? "T=*" : protocol) + ")");
			log.flush();
			card = terminal.connect(protocol);
			String atr = HexUtils.encodeHexString(card.getATR().getBytes());
			log.println(" -> " + card.getProtocol() + ", " + atr);
			if (dump != null) {
				String ts = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z").format(Calendar.getInstance().getTime());
				dump.println("# Generated on " + ts + " by apdu4j");
				dump.println("# Using " + terminal.getName());
				dump.println("# ATR: " + atr);
				dump.println("# PROTOCOL: " + card.getProtocol());
				dump.println("#");
			}
		}

		@Override
		public void beginExclusive() throws CardException {
			log.println("SCardBeginTransaction(\""+terminal.getName() +"\")");
			card.beginExclusive();
		}

		@Override
		public void disconnect(boolean arg0) throws CardException {
			log.println("SCardDisconnect(\""+terminal.getName() +"\", " + arg0 +")");
			if (dump != null) {
				dump.close();
			}
			card.disconnect(arg0);
		}

		@Override
		public void endExclusive() throws CardException {
			log.println("SCardEndTransaction()");
			card.endExclusive();
		}

		@Override
		public ATR getATR() {
			return card.getATR();
		}

		@Override
		public CardChannel getBasicChannel() {
			return new LoggingCardChannel(card);
		}

		@Override
		public String getProtocol() {
			return card.getProtocol();
		}

		@Override
		public CardChannel openLogicalChannel() throws CardException {
			throw new CardException("Logical channels not supported");
		}

		@Override
		public byte[] transmitControlCommand(int arg0, byte[] arg1) throws CardException {
			throw new CardException("Control commands not supported");
		}

		public final class LoggingCardChannel extends CardChannel {
			private final CardChannel channel;
			private final Card card;
			public LoggingCardChannel(Card card) {
				this.card = card;
				this.channel = card.getBasicChannel();
			}
			@Override
			public void close() throws CardException {
				channel.close();
			}

			@Override
			public Card getCard() {
				return card;
			}

			@Override
			public int getChannelNumber() {
				return channel.getChannelNumber();
			}

			@Override
			public ResponseAPDU transmit(CommandAPDU apdu) throws CardException {
				byte [] cb = apdu.getBytes();
				int len_end = apdu.getData().length > 255 ? 7 : 5;
				log.print("A>> " + card.getProtocol() + " (4+" + String.format("%04d", apdu.getData().length) + ")");
				log.print(" " + HexUtils.encodeHexString(Arrays.copyOfRange(cb, 0, 4)));

				// Only if Case 2, 3 or 4 APDU
				if (apdu.getBytes().length > 4) {
					int cmdlen = cb[ISO7816.OFFSET_LC] & 0xFF;
					if (cmdlen == 0 && apdu.getData().length > 6) {
						cmdlen = ((cb[ISO7816.OFFSET_LC +1] & 0xff << 8) | cb[ISO7816.OFFSET_LC+2] & 0xff);
					}
					log.print(" " + HexUtils.encodeHexString(Arrays.copyOfRange(cb, 4, len_end)));
					log.print(" " + HexUtils.encodeHexString(Arrays.copyOfRange(cb, len_end, len_end + cmdlen)));
					if (len_end + cmdlen < cb.length) {
						log.println(" " + HexUtils.encodeHexString(Arrays.copyOfRange(cb, len_end + cmdlen, cb.length)));
					} else {
						log.println();
					}
				} else {
					log.println();
				}
				log.flush();

				long t = System.currentTimeMillis();
				ResponseAPDU response = channel.transmit(apdu);
				long ms = System.currentTimeMillis() - t;
				String time = ms + "ms";
				if (ms > 1000) {
					time = ms / 1000 + "s" + ms % 1000 + "ms";
				}
				byte [] rb = response.getBytes();
				System.out.print("A<< (" + String.format("%04d", response.getData().length) + "+2) (" + time + ")");
				if (response.getData().length > 2) {
					log.print(" " + HexUtils.encodeHexString(response.getData()));
				}
				log.println(" " + HexUtils.encodeHexString(Arrays.copyOfRange(rb, rb.length-2, rb.length)));
				if (dump != null) {
					dump.println("# Sent\n" + HexUtils.encodeHexString(cb));
					dump.println("# Received in " + time + "\n" + HexUtils.encodeHexString(rb));
				}
				return response;
			}

			@Override
			public int transmit(ByteBuffer cmd, ByteBuffer rsp) throws CardException {
				byte[] commandBytes = new byte[cmd.remaining()];
				cmd.get(commandBytes);
				cmd.position(0);

				log.println("B>> " + card.getProtocol() + " (" + commandBytes.length + ") " + HexUtils.encodeHexString(commandBytes));
				int response = channel.transmit(cmd, rsp);
				byte[] responseBytes = new byte[response];
				rsp.get(responseBytes);
				rsp.position(0);
				log.println("B<< (" + responseBytes.length + ") " + HexUtils.encodeHexString(responseBytes));
				if (dump != null) {
					dump.println("# Sent\n" + HexUtils.encodeHexString(commandBytes));
					dump.println("# Received\n" + HexUtils.encodeHexString(responseBytes));
				}
				return response;
			}
		}
	}
}
