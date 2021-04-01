/*
 * Copyright (c) 2019-present Martin Paljak
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
package apdu4j.pcsc.providers;

import apdu4j.core.HexUtils;

import javax.smartcardio.*;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public class APDUReplayProvider extends EmulatedSingleTerminalProvider {
    static final long serialVersionUID = -8337184383179443730L;

    public static final String READER_NAME = "APDUReplay terminal 0";

    public APDUReplayProvider() {
        super(APDUReplayProviderImpl.class);
    }


    public static class APDUReplayProviderImpl extends EmulatedTerminalFactorySpi {

        public APDUReplayProviderImpl(Object parameter) {
            super(parameter);
        }

        @Override
        protected CardTerminal getTheTerminal() {
            if (parameter instanceof InputStream) {
                return new ReplayTerminal((InputStream) parameter, true);
            }
            throw new IllegalArgumentException(getClass().getSimpleName() + " requires InputStream parameter");
        }
    }


    public static final class ReplayTerminal extends CardTerminal {
        private static final String PROTOCOL = "# PROTOCOL: ";
        private static final String ATR = "# ATR: ";

        ATR atr;
        String protocol;

        final boolean strict;
        List<byte[]> commands = null;
        List<byte[]> responses = null;


        public synchronized byte[] replay_transmit(byte[] cmd) throws CardException {
            if (commands.size() == 0) {
                throw new CardException("Replay script depleted!");
            }
            byte[] expected_cmd = commands.remove(0);
            if (strict) {
                if (!Arrays.equals(cmd, expected_cmd)) {
                    throw new CardException("replay: expected " + HexUtils.bin2hex(expected_cmd) + " but got " + HexUtils.bin2hex(cmd));
                }
            }
            return responses.remove(0);
        }

        private void parseScript(InputStream script_stream) {
            Scanner script = new Scanner(script_stream, StandardCharsets.UTF_8.name());
            commands = new ArrayList<>();
            responses = new ArrayList<>();
            boolean is_cmd = true;
            // Parse script file and fail to initiate if it can not be parsed
            while (script.hasNextLine()) {
                String l = script.nextLine().trim();
                // Skip comments
                if (l.startsWith("#")) {
                    if (l.startsWith(ATR)) {
                        atr = new ATR(HexUtils.hex2bin(l.substring(ATR.length())));
                    } else if (l.startsWith(PROTOCOL)) {
                        protocol = l.substring(PROTOCOL.length());
                    }
                    continue;
                }
                byte[] r = HexUtils.hex2bin(l);
                if (is_cmd) {
                    commands.add(r);
                } else {
                    responses.add(r);
                }
                // flip
                is_cmd = !is_cmd;
            }
            // Consistency check
            if (atr == null || protocol == null || responses.size() == 0 || responses.size() != commands.size()) {
                throw new IllegalArgumentException("Incomplete APDU dump!");
            }
        }

        public ReplayTerminal(InputStream script, boolean strict) {
            parseScript(script);
            this.strict = strict;
        }

        @Override
        public Card connect(String protocol) throws CardException {
            return new ReplayCard();
        }

        @Override
        public String getName() {
            return READER_NAME;
        }

        @Override
        public boolean isCardPresent() throws CardException {
            return true;
        }

        @Override
        public boolean waitForCardAbsent(long arg0) throws CardException {
            try {
                Thread.sleep(arg0);
            } catch (InterruptedException e) {
                throw new CardException("Interrupted", e);
            }
            return false;
        }

        @Override
        public boolean waitForCardPresent(long arg0) throws CardException {
            return true;
        }

        public final class ReplayCard extends Card {
            private final CardChannel basicChannel;

            ReplayCard() {
                basicChannel = new ReplayCard.ReplayChannel(this);
            }

            @Override
            public void beginExclusive() throws CardException {

            }

            @Override
            public void disconnect(boolean reset) throws CardException {

            }

            @Override
            public void endExclusive() throws CardException {

            }

            @Override
            public ATR getATR() {
                return atr;
            }

            @Override
            public CardChannel getBasicChannel() {
                return basicChannel;
            }

            @Override
            public String getProtocol() {
                return protocol;
            }

            @Override
            public CardChannel openLogicalChannel() throws CardException {
                throw new CardException("Logical channels not supported");
            }

            @Override
            public byte[] transmitControlCommand(int arg0, byte[] arg1) throws CardException {
                throw new RuntimeException("Control commands don't make sense");
            }


            public final class ReplayChannel extends CardChannel {
                final Card card;

                protected ReplayChannel(Card card) {
                    this.card = card;
                }

                @Override
                public void close() throws CardException {
                    // As only basic logical channel is supported
                    throw new IllegalStateException("Can't close basic channel");
                }

                @Override
                public Card getCard() {
                    return card;
                }

                @Override
                public int getChannelNumber() {
                    return 0;
                }

                @Override
                public ResponseAPDU transmit(CommandAPDU apdu) throws CardException {
                    return new ResponseAPDU(replay_transmit(apdu.getBytes()));
                }

                @Override
                public int transmit(ByteBuffer arg0, ByteBuffer arg1) throws CardException {
                    byte[] cmd = new byte[arg0.remaining()];
                    arg0.get(cmd);
                    byte[] resp = replay_transmit(cmd);
                    arg1.put(resp);
                    return resp.length;
                }
            }
        }
    }
}
