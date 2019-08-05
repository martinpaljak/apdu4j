/*
 * Copyright (c) 2014-2019 Martin Paljak
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

import jnasmartcardio.Smartcardio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.TextOutputCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.smartcardio.*;
import javax.smartcardio.CardTerminals.State;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Facilitates working with javax.smartcardio
 */
public final class TerminalManager {
    static final String LIB_PROP = "sun.security.smartcardio.library";
    private static final Logger logger = LoggerFactory.getLogger(TerminalManager.class);
    private static final String debian64_path = "/usr/lib/x86_64-linux-gnu/libpcsclite.so.1";
    private static final String ubuntu_path = "/lib/libpcsclite.so.1";
    private static final String ubuntu32_path = "/lib/i386-linux-gnu/libpcsclite.so.1";
    private static final String ubuntu64_path = "/lib/x86_64-linux-gnu/libpcsclite.so.1";
    private static final String freebsd_path = "/usr/local/lib/libpcsclite.so";
    private static final String fedora64_path = "/usr/lib64/libpcsclite.so.1";
    private static final String raspbian_path = "/usr/lib/arm-linux-gnueabihf/libpcsclite.so.1";

    /**
     * Locates PC/SC shared library on the system and automagically sets system properties so that SunPCSC
     * could find the smart card service. Call this before acquiring your TerminalFactory.
     */
    public static void fixPlatformPaths() {
        if (System.getProperty(LIB_PROP) == null) {
            // Set necessary parameters for seamless PC/SC access.
            // http://ludovicrousseau.blogspot.com.es/2013/03/oracle-javaxsmartcardio-failures.html
            if (System.getProperty("os.name").equalsIgnoreCase("Linux")) {
                // Only try loading 64b paths if JVM can use them.
                if (System.getProperty("os.arch").contains("64")) {
                    if (new File(debian64_path).exists()) {
                        System.setProperty(LIB_PROP, debian64_path);
                    } else if (new File(fedora64_path).exists()) {
                        System.setProperty(LIB_PROP, fedora64_path);
                    } else if (new File(ubuntu64_path).exists()) {
                        System.setProperty(LIB_PROP, ubuntu64_path);
                    }
                } else if (new File(ubuntu_path).exists()) {
                    System.setProperty(LIB_PROP, ubuntu_path);
                } else if (new File(ubuntu32_path).exists()) {
                    System.setProperty(LIB_PROP, ubuntu32_path);
                } else if (new File(raspbian_path).exists()) {
                    System.setProperty(LIB_PROP, raspbian_path);
                } else {
                    // XXX: dlopen() works properly on Debian OpenJDK 7
                    // System.err.println("Hint: pcsc-lite probably missing.");
                }
            } else if (System.getProperty("os.name").equalsIgnoreCase("FreeBSD")) {
                if (new File(freebsd_path).exists()) {
                    System.setProperty(LIB_PROP, freebsd_path);
                } else {
                    System.err.println("Hint: pcsc-lite is missing. pkg install devel/libccid");
                }
            }
        } else {
            // TODO: display some helpful information?
        }
    }

    static boolean ignoreReader(String ignore, String name) {
        if (ignore != null) {
            String[] names = ignore.toLowerCase().split(";");
            for (String n : names) {
                if (name.toLowerCase().contains(n)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static TerminalFactory getTerminalFactory() throws NoSuchAlgorithmException {
        return TerminalFactory.getInstance("PC/SC", null, new Smartcardio());
    }

    public static void listReaders(String ignore, List<CardTerminal> terminals, PrintStream to, boolean pinpad) {
        try {
            for (CardTerminal t : terminals) {
                String vmd = "";
                if (pinpad) {
                    try (PinPadTerminal pp = PinPadTerminal.getInstance(t)) {
                        pp.probe();
                        // Verify, Modify, Display
                        vmd += pp.canVerify() ? "V" : " ";
                        vmd += pp.canModify() ? "M" : " ";
                        vmd += pp.hasDisplay() ? "D" : " ";
                    } catch (CardException e) {
                        String err = TerminalManager.getExceptionMessage(e);
                        if (err.equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                            vmd = "   ";
                        } else
                            vmd = "EEE";
                    }
                    vmd = "[" + vmd + "] ";
                }
                String present = " ";
                if (ignoreReader(ignore, t.getName())) {
                    present = "I";
                } else if (t.isCardPresent()) {
                    present = "*";
                }
                String secondline = null;

                if (t.isCardPresent()) {
                    Card c = null;
                    byte[] atr = null;
                    // Try shared mode, to detect exclusive mode via exception
                    try {
                        c = t.connect("*");
                        atr = c.getATR().getBytes();
                    } catch (CardException e) {
                        String err = TerminalManager.getExceptionMessage(e);
                        // Detect exclusive mode. Hopes this always succeeds
                        if (err.equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                            present = "X";
                            try {
                                c = t.connect("DIRECT");
                                atr = c.getATR().getBytes();
                            } catch (CardException e2) {
                                String err2 = TerminalManager.getExceptionMessage(e2);
                                if (err2.equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                                    present = "X";
                                }
                            }
                        } else {
                            secondline = "          " + err;
                        }
                    } finally {
                        if (c != null)
                            c.disconnect(false);
                    }

                    if (atr != null) {
                        secondline = "          " + HexUtils.bin2hex(atr).toUpperCase();
                    }
                }

                to.println(String.format("[%s] %s%s", present, vmd, t.getName()));
                if (secondline != null)
                    to.println(secondline);
            }
        } catch (CardException e) {
            logger.error("Failed to print reader list: " + e.getMessage(), e);
        }
    }

    public static Optional<CardTerminal> getTheReader(String arg, String ignore, Collection<byte[]> atrs, long wait) throws CardException, NoSuchAlgorithmException {
        TerminalFactory tf = TerminalFactory.getInstance("PC/SC", null, new jnasmartcardio.Smartcardio());
        CardTerminals ts = tf.terminals();

        List<CardTerminal> terminals = ts.list();

        // DWIM: wait for a reader if none present
        if (terminals.size() == 0 && wait > 0) {
            if (ts.waitForChange(wait)) {
                ts = tf.terminals();
                terminals = ts.list();
                if (terminals.size() == 1) {
                    return Optional.of(terminals.get(0));
                }
            } else {
                logger.warn("Timeout waiting for a NFC reader");
                return Optional.empty();
            }
        }

        if (arg != null) {
            // DWIM 1: specify reader by human index, 1..9
            try {
                int n = Integer.parseInt(arg);
                if (n > 0 && n < 10 && n <= terminals.size()) {
                    return Optional.of(terminals.get(n - 1));
                } else {
                    logger.error(n + ": only have " + terminals.size() + " readers.");
                    listReaders(ignore, terminals, System.err, false);
                }
            } catch (NumberFormatException e) {
                // ignore
            }

            // DWIM 2: portion of the name
            List<CardTerminal> matchingName = terminals.stream().filter(t -> t.getName().toLowerCase().contains(arg)).collect(Collectors.toList());
            if (matchingName.size() == 1) {
                return Optional.of(matchingName.get(0));
            } else if (matchingName.size() == 0) {
                logger.error(String.format("No reader matches '%s'", arg));
                return Optional.empty();
            } else {
                logger.warn(String.format("Multiple readers contain '%s'", arg));
                listReaders(ignore, terminals, System.err, false);
            }
        } else {
            // If just one reader, simple
            if (terminals.size() == 0) {
                logger.error("No smart card readers available");
                return Optional.empty();
            } else if (terminals.size() == 1) {
                return Optional.of(terminals.get(0));
            } else {
                // Multiple readers.
                // DWIM 1: Only one has card
                List<CardTerminal> withCard = ts.list(State.CARD_PRESENT);
                if (withCard.size() == 1) {
                    return Optional.of(withCard.get(0));
                }

                // DWIM 2: Only one matches ATR hints
                List<CardTerminal> withAtr = byATR(ts.list(State.CARD_PRESENT), atrs);
                if (withAtr.size() == 1) {
                    return Optional.of(withAtr.get(0));
                }
                // DWIM 3: TODO: no cards in any, wait for insertion
                System.err.println("Multiple readers, must choose one:");
                listReaders(ignore, terminals, System.err, false);
            }
        }
        return Optional.empty();
    }


    /**
     * Return a list of CardTerminal-s that contain a card with one of the specified ATR-s.
     * The reader might be unusable (in use in exclusive mode).
     *
     * @param terminals List of CardTerminal-s to use
     * @param atrs      Collection of ATR-s to match
     * @return list of CardTerminal-s
     */
    public static List<CardTerminal> byATR(List<CardTerminal> terminals, Collection<byte[]> atrs) {
        return terminals.stream().filter(t -> {
            try {
                if (t.isCardPresent()) {
                    Card c = t.connect("DIRECT");
                    byte[] atr = c.getATR().getBytes();
                    c.disconnect(false);
                    return atrs.stream().anyMatch(a -> Arrays.equals(a, atr));
                } else {
                    return false;
                }
            } catch (CardException e) {
                logger.warn("Failed to get ATR: " + e.getMessage(), e);
                return false;
            }
        }).collect(Collectors.toList());
    }

    public static List<CardTerminal> byATR(CardTerminals terminals, Collection<byte[]> atrs) throws CardException {
        List<CardTerminal> tl = terminals.list(State.ALL);
        return byATR(tl, atrs);
    }

    public static List<CardTerminal> byATR(Collection<byte[]> atrs) throws NoSuchAlgorithmException, CardException {
        TerminalFactory tf = TerminalFactory.getInstance("PC/SC", null, new jnasmartcardio.Smartcardio());
        CardTerminals terminals = tf.terminals();
        return byATR(terminals, atrs);
    }


    // Locate a terminal by AID
    public static List<CardTerminal> byAID(List<CardTerminal> terminals, Collection<byte[]> aids) throws CardException {
        return terminals.stream().filter(t -> {
            try {
                if (t.isCardPresent()) {
                    Card c = null;
                    try {
                        c = t.connect("*");
                        for (byte[] aid : aids) {
                            // Try to select the AID
                            javax.smartcardio.CommandAPDU s = new javax.smartcardio.CommandAPDU(0x00, 0xA4, 0x04, 0x00, aid, 256);
                            javax.smartcardio.ResponseAPDU r = c.getBasicChannel().transmit(s);
                            if (r.getSW() == 0x9000) {
                                logger.debug("{} matched for AID {}", t.getName(), HexUtils.bin2hex(aid));
                                return true;
                            }
                        }
                    } catch (CardException e) {
                        logger.trace("Could not connect or select AID", e);
                    } finally {
                        if (c != null)
                            c.disconnect(false);
                    }
                }
            } catch (CardException e) {
                logger.warn("Failed to get AID: " + e.getMessage(), e);
            }
            return false;
        }).collect(Collectors.toList());
    }


    public static List<CardTerminal> byAID(Collection<byte[]> aids) throws NoSuchAlgorithmException, CardException {
        TerminalFactory tf = TerminalFactory.getInstance("PC/SC", null, new jnasmartcardio.Smartcardio());
        CardTerminals ts = tf.terminals();
        return byAID(ts.list(), aids);
    }


    CallbackHandler getCallbackHandler() {
        return new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                for (Callback c : callbacks) {
                    if (c instanceof TextOutputCallback) {
                        TextOutputCallback cb = (TextOutputCallback) c;

                        final String type;
                        switch (cb.getMessageType()) {
                            case TextOutputCallback.INFORMATION:
                                type = "INFORMATION";
                                break;
                            case TextOutputCallback.WARNING:
                                type = "WARNING";
                                break;
                            case TextOutputCallback.ERROR:
                                type = "ERROR";
                                break;
                            default:
                                type = "MESSAGE";
                        }

                        System.out.println(type + ": " + cb.getMessage());
                    }
                }
            }
        };
    }

    // Connect to a card, waiting for a card if necessary
    public static Card connect(CardTerminal terminal, String protocol, CallbackHandler cb) throws CardException {
        try {
            if (!terminal.isCardPresent()) {

                cb.handle(new Callback[]{new TextOutputCallback(TextOutputCallback.INFORMATION, "Waiting for card ...")});
                boolean found = false;
                for (int i = 20; i > 0 && !found; i--) {
                    found = terminal.waitForCardPresent(3000); // Wait for a minute in 3 second rounds
                    System.out.print(".");
                }
                System.out.println();
                if (!found) {
                    throw new CardNotPresentException("Timeout waiting for a card!");
                }
            }
        } catch (IOException | UnsupportedCallbackException e) {
            throw new CardException("Could not connect to card: " + e.getMessage(), e);
        }
        return terminal.connect(protocol);
    }


    private static String getscard(String s) {
        Pattern p = Pattern.compile("SCARD_\\w+");
        Matcher m = p.matcher(s);
        if (m.find()) {
            return m.group();
        }
        return null;
    }

    // Given an instance of some Exception from a PC/SC system,
    // return a meaningful PC/SC error name.
    public static String getExceptionMessage(Exception e) {
        if (e.getCause() != null && e.getCause().getMessage() != null) {
            String s = getscard(e.getCause().getMessage());
            if (s != null)
                return s;
        }
        if (e.getMessage() != null) {
            String s = getscard(e.getMessage());
            if (s != null)
                return s;
        }
        return e.getMessage();
    }

    public static String getVersion() {
        String version = "unknown-development";
        try (InputStream versionfile = TerminalManager.class.getResourceAsStream("pro_version.txt")) {
            if (versionfile != null) {
                try (BufferedReader vinfo = new BufferedReader(new InputStreamReader(versionfile, StandardCharsets.UTF_8))) {
                    version = vinfo.readLine();
                }
            }
        } catch (IOException e) {
            version = "unknown-error";
        }
        return version;
    }
}
