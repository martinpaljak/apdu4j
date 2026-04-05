/*
 * Copyright (c) 2014-present Martin Paljak <martin@martinpaljak.net>
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
package apdu4j.pcsc.terminals;

import apdu4j.core.HexUtils;
import apdu4j.core.LoggingBIBO;
import apdu4j.pcsc.SCard;

import javax.smartcardio.*;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Log everything going through this CardTerminal, also hypothetical SCard API calls for connect/disconnect.
 */
public final class LoggingCardTerminal extends CardTerminal implements AutoCloseable {
    // The actual terminal
    private final CardTerminal terminal;
    private final PrintStream log;

    private LoggingCardTerminal(CardTerminal term, PrintStream log) {
        this.terminal = Objects.requireNonNull(term, "terminal");
        this.log = Objects.requireNonNull(log, "log");
    }

    public static LoggingCardTerminal getInstance(CardTerminal term) {
        return new LoggingCardTerminal(term, System.out);
    }

    public static LoggingCardTerminal getInstance(CardTerminal term, OutputStream logStream) {
        return new LoggingCardTerminal(term, new PrintStream(logStream, true, StandardCharsets.UTF_8));
    }

    @Override
    public Card connect(String arg0) throws CardException {
        Objects.requireNonNull(arg0, "protocol");
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
        log.close();
    }


    public final class LoggingCard extends Card {
        private final long startTime = System.nanoTime();
        private long transactionStartTime;
        private long inBytes = 0;
        private long outBytes = 0;
        private final Card card;

        private LoggingCard(CardTerminal term, String protocol) throws CardException {
            var sb = new StringBuilder();
            sb.append("# SCardConnect(\"%s\", %s)".formatted(term.getName(), "*".equals(protocol) ? "T=*" : protocol));
            try {
                card = term.connect(protocol);
                var atr = card.getATR().getBytes();
                sb.append(" -> %s%s".formatted(card.getProtocol(), atr.length > 0 ? ", " + HexUtils.bin2hex(atr) : ""));
            } catch (Exception e) {
                sb.append(" -> " + SCard.getExceptionMessage(e));
                throw e;
            } finally {
                log.println(sb);
            }
        }

        @Override
        public void beginExclusive() throws CardException {
            log.println("# SCardBeginTransaction(\"%s\")".formatted(terminal.getName()));
            card.beginExclusive();
            transactionStartTime = System.nanoTime();
        }

        @Override
        public void disconnect(boolean arg0) throws CardException {
            var duration = System.nanoTime() - startTime;
            log.println("# SCardDisconnect(\"%s\", %s) tx:%d/rx:%d in %s".formatted(terminal.getName(), arg0, outBytes, inBytes, LoggingBIBO.nanoTime(duration)));
            inBytes = outBytes = 0;
            card.disconnect(arg0);
        }

        @Override
        public void endExclusive() throws CardException {
            var duration = System.nanoTime() - transactionStartTime;
            transactionStartTime = 0;
            log.println("# SCardEndTransaction(\"%s\") in %s".formatted(terminal.getName(), LoggingBIBO.nanoTime(duration)));
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
            var mc = new StringBuilder();
            mc.append("# MANAGE CHANNEL - OPEN -> ");
            try {
                var c = card.openLogicalChannel();
                mc.append(c.getChannelNumber());
                // MANAGE CHANNEL OPEN: 00 70 00 00 01 (5 bytes) -> channel + SW (3 bytes)
                outBytes += 5;
                inBytes += 3;
                return new LoggingCardChannel(card, c);
            } catch (Exception e) {
                mc.append(SCard.getExceptionMessage(e));
                throw e;
            } finally {
                log.println(mc);
            }
        }

        @Override
        public byte[] transmitControlCommand(int arg0, byte[] arg1) throws CardException {
            var sb = new StringBuilder();
            sb.append("# SCardControl(\"%s\", 0x%08X, %s)".formatted(terminal.getName(), arg0, nil(arg1) ? "null" : HexUtils.bin2hex(arg1)));
            final byte[] result;
            var t = System.nanoTime();
            try {
                result = card.transmitControlCommand(arg0, arg1);
                sb.append(" -> " + (nil(result) ? "null" : HexUtils.bin2hex(result)));
                return result;
            } catch (Exception e) {
                sb.append(" -> " + SCard.getExceptionMessage(e));
                throw e;
            } finally {
                sb.append(" (%s)".formatted(LoggingBIBO.nanoTime(System.nanoTime() - t)));
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
                if (getChannelNumber() != 0) {
                    log.println("# MANAGE CHANNEL - CLOSE #" + getChannelNumber());
                    // MANAGE CHANNEL CLOSE: 00 70 80 XX (4 bytes) -> SW (2 bytes)
                    outBytes += 4;
                    inBytes += 2;
                }
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
                Objects.requireNonNull(apdu, "command APDU");
                return new javax.smartcardio.ResponseAPDU(transmit(apdu.getBytes(), null, null));
            }

            @Override
            public int transmit(ByteBuffer command, ByteBuffer response) throws CardException {
                Objects.requireNonNull(command, "command buffer");
                Objects.requireNonNull(response, "response buffer");
                return transmit(null, command, response).length;
            }

            // commandBytes != null: APDU path (A), command/response != null: ByteBuffer path (B)
            private byte[] transmit(byte[] commandBytes, ByteBuffer command, ByteBuffer response) throws CardException {
                var apduPath = commandBytes != null;
                var prefix = apduPath ? "A" : "B";
                if (!apduPath) {
                    commandBytes = new byte[command.duplicate().remaining()];
                    command.duplicate().get(commandBytes);
                }
                var ch = getChannelNumber() != 0 ? "#" + getChannelNumber() : "";
                String cmdLog;
                int nc = 0;
                try {
                    var cmd = new apdu4j.core.CommandAPDU(commandBytes);
                    nc = cmd.getNc();
                    cmdLog = cmd.toLogString();
                } catch (IllegalArgumentException e) {
                    cmdLog = HexUtils.bin2hex(commandBytes) + " [malformed]";
                }
                log.println("%s%s>> %-8s %s".formatted(prefix, ch, "(4+%04d)".formatted(nc), cmdLog));
                log.flush();

                var t = System.nanoTime();
                try {
                    final byte[] rb;
                    if (apduPath) {
                        rb = channel.transmit(new javax.smartcardio.CommandAPDU(commandBytes)).getBytes();
                    } else {
                        var pos = response.position();
                        var n = channel.transmit(command, response);
                        var dup = response.duplicate();
                        dup.position(pos).limit(pos + n);
                        rb = new byte[n];
                        dup.get(rb);
                    }
                    outBytes += commandBytes.length;
                    inBytes += rb.length;
                    var resp = new apdu4j.core.ResponseAPDU(rb);
                    log.println("%s%s<< %-8s (%s) %s".formatted(prefix, ch, "(%04d+2)".formatted(resp.getData().length), LoggingBIBO.nanoTime(System.nanoTime() - t), resp.toLogString()));
                    return rb;
                } catch (Exception e) {
                    log.println("<< %s (%s)".formatted(SCard.getExceptionMessage(e), LoggingBIBO.nanoTime(System.nanoTime() - t)));
                    throw e;
                }
            }
        }
    }

    private static boolean nil(byte[] v) {
        return v == null || v.length == 0;
    }
}
