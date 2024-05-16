/**
 * Copyright (c) 2014-2017 Martin Paljak
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
package apdu4j.pcsc.terminals;

import apdu4j.core.HexUtils;
import apdu4j.pcsc.SCard;
import apdu4j.pcsc.TerminalManager;

import javax.smartcardio.*;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;

/**
 * Log everything going through this CardTerminal, also hypothetical SCard API calls for connect/disconnect.
 * <p>
 * Dump APDU-s to a file for automatic playback and inspection
 *
 * @author Martin Paljak
 */
public final class LoggingCardTerminal extends CardTerminal implements AutoCloseable {
    // The actual terminal
    protected final CardTerminal terminal;
    protected final PrintStream log;
    protected final PrintStream dump;
    protected long startTime;

    private LoggingCardTerminal(CardTerminal term, PrintStream log, PrintStream dump) {
        if (term == null)
            throw new IllegalArgumentException("terminal==null");
        this.terminal = term;
        this.log = log;
        this.dump = dump;
    }

    public static LoggingCardTerminal getInstance(CardTerminal term) {
        return new LoggingCardTerminal(term, System.out, null);
    }

    public static LoggingCardTerminal getInstance(CardTerminal term, OutputStream logStream) {
        return new LoggingCardTerminal(term, new PrintStream(logStream, true, StandardCharsets.UTF_8), null);
    }

    public static LoggingCardTerminal getInstance(CardTerminal term, OutputStream logStream, OutputStream dump) {
        return new LoggingCardTerminal(term, new PrintStream(logStream, true, StandardCharsets.UTF_8), new PrintStream(dump, true, StandardCharsets.UTF_8));
    }

    @Override
    public Card connect(String arg0) throws CardException {
        startTime = System.currentTimeMillis();
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

    @Override
    public void close() {
        if (log != null)
            log.close();
        if (dump != null)
            dump.close();
    }


    public final class LoggingCard extends Card {
        private long inBytes = 0;
        private long outBytes = 0;
        private final Card card;

        private LoggingCard(CardTerminal term, String protocol) throws CardException {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("# SCardConnect(\"%s\", %s)", term.getName(), protocol.equals("*") ? "T=*" : protocol));
            try {
                card = term.connect(protocol);
                byte[] atr = card.getATR().getBytes();
                sb.append(" -> " + card.getProtocol() + (atr.length > 0 ? ", " + HexUtils.bin2hex(atr) : ""));
                if (dump != null) {
                    String ts = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z").format(Calendar.getInstance().getTime());
                    dump.println("# Generated on " + ts + " by apdu4j/" + TerminalManager.getVersion());
                    dump.println("# Using " + term.getName());
                    dump.println("# ATR: " + HexUtils.bin2hex(atr));
                    dump.println("# PROTOCOL: " + card.getProtocol());
                    dump.println("#");
                }
            } catch (CardException e) {
                sb.append(" -> " + SCard.getExceptionMessage(e));
                throw e;
            } finally {
                log.println(sb);
            }
        }

        @Override
        public void beginExclusive() throws CardException {
            log.println(String.format("# SCardBeginTransaction(\"%s\")", terminal.getName()));
            card.beginExclusive();
        }

        @Override
        public void disconnect(boolean arg0) throws CardException {
            long duration = System.currentTimeMillis() - startTime;
            log.println(String.format("# SCardDisconnect(\"%s\", %s) tx:%d/rx:%d in %s", terminal.getName(), arg0, outBytes, inBytes, time(duration)));
            inBytes = outBytes = 0;
            if (dump != null) {
                dump.close();
            }
            card.disconnect(arg0);
        }

        @Override
        public void endExclusive() throws CardException {
            log.println(String.format("# SCardEndTransaction(\"%s\")", terminal.getName()));
            card.endExclusive();
        }

        @Override
        public ATR getATR() {
            return card.getATR();
        }

        @Override
        public CardChannel getBasicChannel() {
            return new LoggingCardChannel(card, card.getBasicChannel());
        }

        @Override
        public String getProtocol() {
            return card.getProtocol();
        }

        @Override
        public CardChannel openLogicalChannel() throws CardException {
            // FIXME - would want to "see" MANAGE CHANNEL APDU-s as well.
            StringBuilder mc = new StringBuilder();
            mc.append("# MANAGE CHANNEL - OPEN -> ");
            try {
                CardChannel c = card.openLogicalChannel();
                mc.append(c.getChannelNumber());
                return new LoggingCardChannel(card, c);
            } catch (CardException e) {
                mc.append(SCard.getPCSCError(e).orElse("Exception (" + e.getMessage() + ")"));
                throw e;
            } finally {
                log.println(mc);
            }
        }

        @Override
        public byte[] transmitControlCommand(int arg0, byte[] arg1) throws CardException {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("# SCardControl(\"%s\", 0x%08X, %s)", terminal.getName(), arg0, nil(arg1) ? "null" : HexUtils.bin2hex(arg1)));
            final byte[] result;
            try {
                result = card.transmitControlCommand(arg0, arg1);
                sb.append(" -> " + (nil(result) ? "null" : HexUtils.bin2hex(result)));
                return result;
            } catch (CardException e) {
                sb.append("-> " + SCard.getPCSCError(e).orElse("Exception"));
                throw e;
            } finally {
                log.println(sb);
            }
        }

        public final class LoggingCardChannel extends CardChannel {
            private final CardChannel channel;
            private final Card card;

            public LoggingCardChannel(Card card, CardChannel channel) {
                this.card = card;
                this.channel = channel;
            }

            @Override
            public void close() throws CardException {
                if (getChannelNumber() != 0)
                    log.println("# MANAGE CHANNEL - CLOSE");
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
            public javax.smartcardio.ResponseAPDU transmit(javax.smartcardio.CommandAPDU apdu) throws CardException {
                byte[] cb = apdu.getBytes();
                boolean extended = cb.length > 5 && cb[4] == 0x00;
                int len_end = extended ? 7 : 5;
                String header = HexUtils.bin2hex(Arrays.copyOfRange(cb, 0, 4));
                StringBuilder log_s = new StringBuilder();
                log_s.append(String.format("A%s>> %s (4+%04d) %s", getChannelNumber() != 0 ? String.format("#%d", getChannelNumber()) : "", card.getProtocol(), apdu.getData().length, header));

                // Only if Case 2, 3 or 4 APDU
                if (cb.length > 4) {
                    int cmdlen = cb[4] & 0xFF;

                    // If extended length
                    if (cmdlen == 0 && extended) {
                        cmdlen = ((cb[5] & 0xff << 8) | cb[6] & 0xff);
                    }
                    // P3 is Le
                    if (apdu.getData().length < cmdlen) {
                        cmdlen = 0;
                    }
                    // print length bytes
                    log_s.append(" " + HexUtils.bin2hex(Arrays.copyOfRange(cb, 4, len_end)));
                    // print payload
                    log_s.append(" " + HexUtils.bin2hex(Arrays.copyOfRange(cb, len_end, len_end + cmdlen)));
                    // Print Le
                    if (len_end + cmdlen < cb.length) {
                        log_s.append(" " + HexUtils.bin2hex(Arrays.copyOfRange(cb, len_end + cmdlen, cb.length)));
                    }
                }
                log.println(log_s);
                log.flush();

                long t = System.currentTimeMillis();
                final javax.smartcardio.ResponseAPDU response;
                try {
                    response = channel.transmit(apdu);
                    outBytes += cb.length;
                } catch (CardException e) {
                    String time = time(System.currentTimeMillis() - t);
                    log.println("<< (" + time + ") " + SCard.getPCSCError(e).orElse("Exception (" + e.getMessage() + ")"));
                    throw e;
                }

                String time = time(System.currentTimeMillis() - t);

                StringBuilder log_r = new StringBuilder();
                byte[] rb = response.getBytes();
                inBytes += rb.length;
                log_r.append(String.format("A%s<< (%04d+2) (%s)", getChannelNumber() != 0 ? String.format("#%d", getChannelNumber()) : "", response.getData().length, time));
                if (rb.length > 2) {
                    log_r.append(" " + HexUtils.bin2hex(Arrays.copyOfRange(rb, 0, rb.length - 2)));
                }
                log_r.append(" " + HexUtils.bin2hex(Arrays.copyOfRange(rb, rb.length - 2, rb.length)));
                log.println(log_r);
                if (dump != null) {
                    dump.println("# Sent\n" + HexUtils.bin2hex(cb));
                    dump.println("# Received in " + time + "\n" + HexUtils.bin2hex(rb));
                }
                return response;
            }


            @Override
            public int transmit(ByteBuffer command, ByteBuffer response) throws CardException {
                ByteBuffer commandCopy = command.asReadOnlyBuffer();
                byte[] commandBytes = new byte[commandCopy.remaining()];
                commandCopy.get(commandBytes);
                log.println("B>> " + card.getProtocol() + " (" + commandBytes.length + ") " + HexUtils.bin2hex(commandBytes));

                ByteBuffer responseCopy = response.asReadOnlyBuffer();
                responseCopy.mark();
                int resplen = channel.transmit(command, response);
                outBytes += commandBytes.length;

                byte[] responseBytes = new byte[resplen];
                responseCopy.reset();
                responseCopy.get(responseBytes);
                inBytes += responseBytes.length;
                log.println("B<< (" + responseBytes.length + ") " + HexUtils.bin2hex(responseBytes));

                if (dump != null) {
                    dump.println("# Sent\n" + HexUtils.bin2hex(commandBytes));
                    dump.println("# Received\n" + HexUtils.bin2hex(responseBytes));
                }
                return resplen;
            }
        }
    }

    private static String time(long ms) {
        String time = ms + "ms";
        if (ms > 1000) {
            time = ms / 1000 + "s" + ms % 1000 + "ms";
        }
        return time;
    }

    private static boolean nil(byte[] v) {
        return v == null || v.length == 0;
    }
}
