/*
 * Copyright (c) 2014-present Martin Paljak
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
package apdu4j.pcsc;

import apdu4j.core.APDUBIBO;
import apdu4j.core.BIBO;
import apdu4j.core.HexUtils;
import apdu4j.core.CommandAPDU;
import apdu4j.core.ResponseAPDU;

import jnasmartcardio.Smartcardio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.*;
import javax.smartcardio.CardTerminals.State;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Facilitates working with javax.smartcardio
 */
public final class TerminalManager {
    public static final String PROTOCOL_PROPERTY = "apdu4j.protocol";
    public static final String LIB_PROP = "sun.security.smartcardio.library";

    private static final Logger logger = LoggerFactory.getLogger(TerminalManager.class);
    private static final String debian64_path = "/usr/lib/x86_64-linux-gnu/libpcsclite.so.1";
    private static final String ubuntu_path = "/lib/libpcsclite.so.1";
    private static final String ubuntu32_path = "/lib/i386-linux-gnu/libpcsclite.so.1";
    private static final String ubuntu64_path = "/lib/x86_64-linux-gnu/libpcsclite.so.1";
    private static final String freebsd_path = "/usr/local/lib/libpcsclite.so";
    private static final String fedora64_path = "/usr/lib64/libpcsclite.so.1";
    private static final String raspbian_path = "/usr/lib/arm-linux-gnueabihf/libpcsclite.so.1";

    private final List<CardTerminal> terminals;

    static String _detectLibraryPath() {
        // Set necessary parameters for seamless PC/SC access.
        // http://ludovicrousseau.blogspot.com.es/2013/03/oracle-javaxsmartcardio-failures.html
        if (System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            // Only try loading 64b paths if JVM can use them.
            if (System.getProperty("os.arch").contains("64")) {
                if (new File(debian64_path).exists()) {
                    return debian64_path;
                } else if (new File(fedora64_path).exists()) {
                    return fedora64_path;
                } else if (new File(ubuntu64_path).exists()) {
                    return ubuntu64_path;
                }
            } else if (new File(ubuntu_path).exists()) {
                return ubuntu_path;
            } else if (new File(ubuntu32_path).exists()) {
                return ubuntu32_path;
            } else if (new File(raspbian_path).exists()) {
                return raspbian_path;
            } else {
                // XXX: dlopen() works properly on Debian OpenJDK 7
                // System.err.println("Hint: pcsc-lite probably missing.");
            }
        } else if (System.getProperty("os.name").equalsIgnoreCase("FreeBSD")) {
            if (new File(freebsd_path).exists()) {
                return freebsd_path;
            } else {
                System.err.println("Hint: pcsc-lite is missing. pkg install devel/libccid");
            }
        }
        return null;
    }

    public static Optional<String> detectLibraryPath() {
        return Optional.ofNullable(_detectLibraryPath());
    }

    /**
     * Locates PC/SC shared library on the system and automagically sets system properties so that SunPCSC
     * could find the smart card service. Call this before acquiring your TerminalFactory.
     */
    public static void fixPlatformPaths() {
        Optional<String> lib = detectLibraryPath();
        if (System.getProperty(LIB_PROP) == null && lib.isPresent()) {
            System.setProperty(LIB_PROP, lib.get());
        }
    }

    // Returns true if the reader contains fragments to ignore (given as semicolon separated string)
    public static boolean ignoreReader(String ignore, String reader) {
        if (ignore != null) {
            String[] names = ignore.toLowerCase().split(";");
            return Arrays.stream(names).anyMatch(e -> reader.toLowerCase().contains(e));
        }
        return false;
    }

    // Utility function to return a "Good terminal factory" (JNA)
    public static TerminalFactory getTerminalFactory() {
        try {
            return TerminalFactory.getInstance("PC/SC", null, new Smartcardio());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("jnasmartcardio not bundled");
        }
    }

    // Utility function that lists known readers
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

    public static TerminalManager getInstance(CardTerminals terminals) throws CardException {
        return getInstance(terminals.list());
    }

    public static TerminalManager getInstance(List<CardTerminal> terminals) {
        return new TerminalManager(terminals);
    }

    private TerminalManager(List<CardTerminal> terminals) {
        this.terminals = terminals;
    }

    // Do some DWIM to get a reader out of many
    public Optional<CardTerminal> dwim(String arg, String ignore, Collection<byte[]> atrs) {
        if (arg != null) {
            // DWIM 1: specify reader by human index, 1..9
            if (arg.length() == 1 && Character.isDigit(arg.charAt(0))) {
                int n = Character.getNumericValue(arg.charAt(0));
                if (n > 0 && n < 10 && n <= terminals.size()) {
                    CardTerminal t = terminals.get(n - 1);
                    logger.debug("Matched {} based on index {}", t.getName(), n);
                    return Optional.of(t);
                } else {
                    logger.error(n + ": only have " + terminals.size() + " readers.");
                    listReaders(ignore, terminals, System.err, false);
                }
            }

            // DWIM 2: portion of the name or alias
            final ReaderAliases alias = ReaderAliases.getDefault();
            final String q = arg.toLowerCase();
            List<CardTerminal> matchingName = terminals.stream().filter(t -> t.getName().toLowerCase().contains(q) || alias.translate(t.getName()).toLowerCase().contains(q)).collect(Collectors.toList());
            if (matchingName.size() == 1) {
                logger.debug("Matched {}", matchingName.get(0));
                return Optional.of(matchingName.get(0));
            } else if (matchingName.size() == 0) {
                logger.error(String.format("No reader matches '%s'", arg));
                return Optional.empty();
            } else {
                logger.warn(String.format("Multiple readers contain '%s'", arg));
                listReaders(ignore, terminals, System.err, false);
            }
        } else {
            // No reader indicated. Try to figure one out automagically
            // No readers
            if (terminals.size() == 0) {
                logger.error("No smart card readers available");
                return Optional.empty();
            }

            // One reader
            if (terminals.size() == 1) {
                if (ignoreReader(ignore, terminals.get(0).getName())) {
                    logger.warn("Only one reader, but ignoring it!");
                    return Optional.empty();
                }
                return Optional.of(terminals.get(0));
            }

            // Multiple readers.
            // DWIM 1: Only one has card
            List<CardTerminal> withCard = terminals.stream().filter(e -> {
                try {
                    return e.isCardPresent() && !ignoreReader(ignore, e.getName());
                } catch (CardException ex) {
                    return false;
                }
            }).collect(Collectors.toList());
            if (withCard.size() == 1) {
                logger.debug("Selected the only reader with a card");
                return Optional.of(withCard.get(0));
            }

            // DWIM 2: Only one matches ATR hints
            List<CardTerminal> withAtr = byATR(terminals, atrs);
            if (withAtr.size() == 1) {
                logger.debug("Selected the only reader with a matching ATR");
                return Optional.of(withAtr.get(0));
            }
            // DWIM 3: TODO: no cards in any, wait for insertion
            System.err.println("Multiple readers, must choose one:");
            listReaders(ignore, terminals, System.err, false);
        }
        return Optional.empty();
    }


    /**
     * Return a list of CardTerminal-s that contain a card with one of the specified ATR-s.
     * The returned reader might be unusable (in use in exclusive mode).
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
                logger.debug("Failed to get ATR: " + e.getMessage(), e);
                return false;
            }
        }).collect(Collectors.toList());
    }

    public static List<CardTerminal> byATR(CardTerminals terminals, Collection<byte[]> atrs) throws CardException {
        List<CardTerminal> tl = terminals.list(State.ALL); // FIXME: ALL was required for a while on OSX due to bugs. Safe to replace with PRESENT?
        return byATR(tl, atrs);
    }

    // Return a list of terminals, filtered by interesting ATR-s
    public static List<CardTerminal> byATR(Collection<byte[]> atrs) throws NoSuchAlgorithmException, CardException {
        TerminalFactory tf = TerminalFactory.getInstance("PC/SC", null, new jnasmartcardio.Smartcardio());
        CardTerminals terminals = tf.terminals();
        return byATR(terminals, atrs);
    }

    public static List<CardTerminal> byFilter(List<CardTerminal> terminals, Function<BIBO, Boolean> f) {
        return terminals.stream().filter(t -> {
            try {
                if (t.isCardPresent()) {
                    try (BIBO b = CardBIBO.wrap(t.connect("*"))) {
                        return f.apply(b);
                    }
                } else {
                    return false;
                }
            } catch (CardException e) {
                // FIXME: handle exclusive mode
                logger.debug("Failed to detect card: " + e.getMessage(), e);
                return false;
            }
        }).collect(Collectors.toList());
    }


    // Useful function for byFilter
    public static Function<BIBO, Boolean> hasAID(Collection<byte[]> aidlist) {
        return bibo -> {
            APDUBIBO b = new APDUBIBO(bibo);
            for (byte[] aid : aidlist) {
                // Try to select the AID
                CommandAPDU s = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, aid, 256);
                ResponseAPDU r = b.transmit(s);
                if (r.getSW() == 0x9000) {
                    logger.debug("matched for AID {}", HexUtils.bin2hex(aid));
                    return true;
                }
            }
            return false;
        };
    }

    // Locate a terminal by AID
    public static List<CardTerminal> byAID(List<CardTerminal> terminals, Collection<byte[]> aidlist) {
        return byFilter(terminals, hasAID(aidlist));
    }

    public static List<CardTerminal> byAID(Collection<byte[]> aidlist) throws NoSuchAlgorithmException, CardException {
        TerminalFactory tf = TerminalFactory.getInstance("PC/SC", null, new jnasmartcardio.Smartcardio());
        CardTerminals ts = tf.terminals();
        return byAID(ts.list(), aidlist);
    }

    private static Optional<String> getscard(String s) {
        if (s == null)
            return Optional.empty();
        Pattern p = Pattern.compile("SCARD_\\w+");
        Matcher m = p.matcher(s);
        if (m.find()) {
            return Optional.ofNullable(m.group());
        }
        return Optional.empty();
    }

    // Given an instance of some Exception from a PC/SC system,
    // return a meaningful PC/SC error name.
    public static String getExceptionMessage(Exception e) {
        return getPCSCError(e).orElse(e.getMessage());
    }

    public static Optional<String> getPCSCError(Throwable e) {
        while (e != null) {
            Optional<String> m = getscard(e.getMessage());
            if (m.isPresent())
                return m;
            e = e.getCause();
        }
        return Optional.empty();
    }

    // Returns CalVer+git of the utility
    public static String getVersion() {
        Properties prop = new Properties();
        try (InputStream versionfile = TerminalManager.class.getResourceAsStream("git.properties")) {
            prop.load(versionfile);
            return prop.getProperty("git.commit.id.describe", "unknown-development");
        } catch (IOException e) {
            return "unknown-error";
        }
    }
}
