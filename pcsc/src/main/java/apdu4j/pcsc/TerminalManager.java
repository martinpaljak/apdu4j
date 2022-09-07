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

import apdu4j.core.CommandAPDU;
import apdu4j.core.ResponseAPDU;
import apdu4j.core.*;
import apdu4j.pcsc.terminals.LoggingCardTerminal;
import jnasmartcardio.Smartcardio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.*;
import javax.smartcardio.CardTerminals.State;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Facilitates working with javax.smartcardio TerminalFactory/CardTerminals
 * <p>
 * Also knows about an alternative implementation, jnasmartcardio
 */
public final class TerminalManager {
    private static final Logger logger = LoggerFactory.getLogger(TerminalManager.class);

    public static final String LIB_PROP = "sun.security.smartcardio.library";

    private static final String debian64_path = "/usr/lib/x86_64-linux-gnu/libpcsclite.so.1";
    private static final String ubuntu_path = "/lib/libpcsclite.so.1";
    private static final String ubuntu32_path = "/lib/i386-linux-gnu/libpcsclite.so.1";
    private static final String ubuntu64_path = "/lib/x86_64-linux-gnu/libpcsclite.so.1";
    private static final String freebsd_path = "/usr/local/lib/libpcsclite.so";
    private static final String fedora64_path = "/usr/lib64/libpcsclite.so.1";
    private static final String raspbian_path = "/usr/lib/arm-linux-gnueabihf/libpcsclite.so.1";

    private final TerminalFactory factory;
    // TODO: this whole threadlocal stuff should end up in jnasmartcardio?
    // it is not static as it is tied to factory instance of this class
    private ThreadLocal<CardTerminals> threadLocalTerminals = ThreadLocal.withInitial(() -> null);


    public static TerminalManager getDefault() {
        return new TerminalManager(getTerminalFactory());
    }

    public TerminalManager(TerminalFactory factory) {
        this.factory = factory;
    }

    public CardTerminals terminals() {
        return terminals(false);
    }

    public CardTerminals terminals(boolean fresh) {
        CardTerminals terms = threadLocalTerminals.get();
        // Explicity release the old context if using jnasmartcardio
        if (terms != null && fresh && terms instanceof Smartcardio.JnaCardTerminals) {
            try {
                ((Smartcardio.JnaCardTerminals) terms).close();
            } catch (Smartcardio.JnaPCSCException e) {
                logger.warn("Could not release context: {}", SCard.getExceptionMessage(e), e);
            }
        }
        if (terms == null || fresh) {
            terms = factory.terminals();
            threadLocalTerminals.set(terms);
        }
        return terms;
    }

    public TerminalFactory getFactory() {
        return factory;
    }

    // Makes sure the associated context would be thread-local for jnasmartcardio and Linux
    public CardTerminal getTerminal(String name) {
        return terminals().getTerminal(name);
    }

    public static boolean isEnabled(String feature, boolean def) {
        return Boolean.parseBoolean(System.getProperty(feature, System.getenv().getOrDefault("_" + feature.toUpperCase().replace(".", "_"), Boolean.toString(def))));
    }

    // SunPCSC needs to have the path to the loadable library to work, for whatever reasons.
    public static String detectLibraryPath() {
        // Would be nice to use Files.exists instead.
        final String os = System.getProperty("os.name");
        // Set necessary parameters for seamless PC/SC access.
        // http://ludovicrousseau.blogspot.com.es/2013/03/oracle-javaxsmartcardio-failures.html
        if (os.equalsIgnoreCase("Linux")) {
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
        } else if (os.equalsIgnoreCase("FreeBSD")) {
            if (new File(freebsd_path).exists()) {
                return freebsd_path;
            } else {
                System.err.println("Hint: pcsc-lite is missing. pkg install devel/libccid");
            }
        } else if (os.equalsIgnoreCase("Mac OS X")) {
            // XXX: research/document this
            return "/System/Library/Frameworks/PCSC.framework/PCSC";
        }
        return null;
    }

    /**
     * Locates PC/SC shared library on the system and automagically sets system properties so that SunPCSC
     * could find the smart card service. Call this before acquiring your TerminalFactory.
     */
    public static void fixPlatformPaths() {
        Optional<String> lib = Optional.ofNullable(detectLibraryPath());
        if (System.getProperty(LIB_PROP) == null && lib.isPresent()) {
            System.setProperty(LIB_PROP, lib.get());
        }
    }

    // Utility function to return a "Good terminal factory" (JNA)
    public static TerminalFactory getTerminalFactory() {
        try {
            return TerminalFactory.getInstance("PC/SC", null, new Smartcardio());
        } catch (NoSuchAlgorithmException e) {
            logger.error("jnasmartcardio not bundled or pcsc-lite not available");
            // Should result in NoneProvider
            return TerminalFactory.getDefault();
        }
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
        List<CardTerminal> tl = terminals.list(State.ALL);
        return byATR(tl, atrs);
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

    public static boolean isContactless(CardTerminal t) {
        return isContactless(t.getName());
    }

    public static boolean isContactless(String reader) {
        String[] contactless = {"Contactless", " PICC", "KP382", "502-CL", "ACR1255U"};
        for (String s : contactless) {
            if (fragmentMatches(s, reader))
                return true;
        }
        return false;
    }

    public static boolean isSpecial(CardTerminal t) {
        return isSpecial(t.getName());
    }

    public static boolean isSpecial(String reader) {
        String[] special = {"YubiKey"};
        for (String s : special) {
            if (fragmentMatches(s, reader))
                return true;
        }
        return false;
    }

    // DWIM magic. See if we can pick the interesting reader automagically
    public static <T> Optional<T> toSingleton(Collection<T> collection, Predicate<T> filter) {
        List<T> result = collection.stream().filter(filter).limit(2).collect(Collectors.toList());
        if (result.size() == 1) return Optional.of(result.get(0));
        return Optional.empty();
    }

    public static Optional<String> hintMatchesExactlyOne(String hint, List<String> hay) {
        if (hint == null)
            return Optional.empty();
        return toSingleton(hay, n -> fragmentMatches(hint, n));
    }

    private static boolean fragmentMatches(String fragment, String longer) {
        return longer.toLowerCase().contains(fragment.toLowerCase());
    }

    // Returns true if the reader contains fragments to ignore (given as semicolon separated string)
    public static boolean ignoreReader(String ignoreHints, String readerName) {
        if (ignoreHints != null) {
            String reader = readerName.toLowerCase();
            String[] names = ignoreHints.toLowerCase().split(";");
            return Arrays.stream(names).anyMatch(reader::contains);
        }
        return false;
    }

    // Fetch what is a combination of CardTerminal + Card data and handle all the weird errors of PC/SC
    public static List<PCSCReader> listPCSC(List<CardTerminal> terminals, OutputStream logStream, boolean probePinpad) throws CardException {
        ArrayList<PCSCReader> result = new ArrayList<>();
        for (CardTerminal t : terminals) {
            if (logStream != null) {
                t = LoggingCardTerminal.getInstance(t, logStream);
            }
            try {
                final String name = t.getName();
                boolean present = t.isCardPresent();
                boolean exclusive = false;
                String vmd = null;
                byte[] atr = null;
                if (present) {
                    Card c = null;
                    // Try to connect in shared mode, also detects EXCLUSIVE
                    try {
                        c = t.connect("*");
                        // If successful, we get the protocol and ATR
                        atr = c.getATR().getBytes();
                        if (probePinpad)
                            vmd = PinPadTerminal.getVMD(t, c);
                    } catch (CardException e) {
                        String err = SCard.getExceptionMessage(e);
                        if (err.equals(SCard.SCARD_W_UNPOWERED_CARD)) {
                            logger.warn("Unpowered card. Contact card inserted wrong way or card mute?");
                            // We don't present such cards, as for contactless this is a no-case TODO: reconsider ?
                            present = false;
                        } else if (err.equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                            exclusive = true;
                            // macOS allows to connect to reader in DIRECT mode when device is in EXCLUSIVE
                            try {
                                c = t.connect("DIRECT");
                                atr = c.getATR().getBytes();
                                if (probePinpad)
                                    vmd = PinPadTerminal.getVMD(t, c);
                            } catch (CardException e2) {
                                String err2 = SCard.getExceptionMessage(e);
                                if (probePinpad)
                                    if (err2.equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                                        vmd = "???";
                                    } else {
                                        vmd = "EEE";
                                        logger.debug("Unexpected error: {}", err2, e2);
                                    }
                            }
                        } else {
                            if (probePinpad) vmd = "EEE";
                            logger.debug("Unexpected error: {}", err, e);
                        }
                    } finally {
                        if (c != null)
                            c.disconnect(false);
                    }
                } else {
                    // Not present
                    if (probePinpad) {
                        Card c = null;
                        // Try to connect in DIRECT mode
                        try {
                            c = t.connect("DIRECT");
                            vmd = PinPadTerminal.getVMD(t, c);
                        } catch (CardException e) {
                            vmd = "EEE";
                            String err = SCard.getExceptionMessage(e);
                            logger.debug("Could not connect to reader in direct mode: {}", err, e);
                        } finally {
                            if (c != null)
                                c.disconnect(false);
                        }
                    }
                }
                result.add(new PCSCReader(name, atr, present, exclusive, vmd));
            } catch (CardException e) {
                String err = SCard.getExceptionMessage(e);
                logger.debug("Unexpected PC/SC error: {}", err, e);
            }
        }
        return result;
    }

    // Set the preferred and ignored flags on a reader list, based on hints. Returns the same list
    public static List<PCSCReader> dwimify(List<PCSCReader> readers, String preferHint, String ignoreHints) {
        logger.debug("Processing {} readers with {} as preferred and {} as ignored", readers.size(), preferHint, ignoreHints);
        final ReaderAliases aliases = ReaderAliases.getDefault().apply(readers.stream().map(PCSCReader::getName).collect(Collectors.toList()));
        final List<String> aliasedNames = readers.stream().map(r -> aliases.extended(r.getName())).collect(Collectors.toList());

        // No readers
        if (readers.size() == 0)
            return readers;

        Optional<String> pref = Optional.empty();
        // DWIM 1: small digit hint means reader index
        if (preferHint != null && preferHint.matches("\\d{1,2}")) {
            int index = Integer.parseInt(preferHint);
            if (index >= 1 && index <= readers.size()) {
                pref = Optional.of(readers.get(index - 1).getName());
                logger.debug("Chose {} by index {}", pref.get(), index);
            } else {
                logger.warn("Reader index out of bounds: {} vs {}", index, readers.size());
            }
        } else if (preferHint != null) {
            pref = TerminalManager.hintMatchesExactlyOne(preferHint, aliasedNames);
        } else if (readers.size() == 1) {
            // One reader
            readers.get(0).setPreferred(true);
            return readers;
        }

        logger.debug("Preferred reader: " + pref);
        // Loop all readers, amending as necessary.
        for (PCSCReader r : readers) {
            if (pref.isPresent() && pref.get().toLowerCase().contains(r.getName().toLowerCase())) {
                r.setPreferred(true);
                // preference overrides ignores
                continue;
            }
            if (TerminalManager.ignoreReader(ignoreHints, r.getName())) {
                r.setIgnore(true);
                continue;
            }
        }

        // "if no preferred, but just a single reader of un-ignored readers contains a card - use it
        //logger.info("Readers after dwimify");
        //for (PCSCReader r : readers) {
        //    logger.info("{}", r);
        //}
        return readers;
    }

    public static Optional<CardTerminal> getLucky(List<PCSCReader> readers, CardTerminals terminals) {
        Optional<PCSCReader> preferred = toSingleton(readers, e -> e.isPreferred());
        Optional<PCSCReader> lucky = toSingleton(readers, e -> !e.isIgnore() && e.isPresent());
        Optional<PCSCReader> chosen = preferred.or(() -> lucky);
        if (chosen.isPresent()) {
            return Optional.ofNullable(terminals.getTerminal(chosen.get().getName()));
        }
        return Optional.empty();
    }

    public static boolean isMacOS() {
        return System.getProperty("os.name").equalsIgnoreCase("mac os x");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().startsWith("windows");
    }
}
