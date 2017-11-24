/*
 * Copyright (c) 2014-2016 Martin Paljak
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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.*;
import javax.smartcardio.CardTerminals.State;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facilitates working with javax.smartcardio
 */
public final class TerminalManager {
    static final String SUN_CLASS = "sun.security.smartcardio.SunPCSC";
    static final String JNA_CLASS = "jnasmartcardio.Smartcardio";
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
    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
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
            // TODO: display some helping information?
        }
    }

    /**
     * Creates a terminal factory.
     * <p>
     * Fixes PC/SC paths for Unix systems or bypasses SunPCSC and uses JNA based approach.
     *
     * @param fix true if jnasmartcardio should be used
     * @return a {@link TerminalFactory} instance
     * @throws NoSuchAlgorithmException if jnasmartcardio is not found
     * @deprecated since v0.0.33 - use {@link #getTerminalFactory(String)} instead
     */
    @Deprecated
    public static TerminalFactory getTerminalFactory(boolean fix) throws NoSuchAlgorithmException {
        fixPlatformPaths();
        if (fix) {
            return TerminalFactory.getInstance("PC/SC", null, new jnasmartcardio.Smartcardio());
        } else {
            return TerminalFactory.getDefault();
        }
    }

    /**
     * Load the TerminalFactory, possibly from a JAR and with arguments
     */
    @SuppressFBWarnings("DP_CREATE_CLASSLOADER_INSIDE_DO_PRIVILEGED") // TODO: clarify and use service loader instead
    public static TerminalFactory loadTerminalFactory(String jar, String classname, String type, String arg) throws NoSuchAlgorithmException {
        try {
            // To support things like host:port pairs, urldecode the arguments component if provided
            if (arg != null) {
                arg = URLDecoder.decode(arg, "UTF-8");
            }
            final TerminalFactory tf;
            final Class<?> cls; // XXX: stricter type
            if (jar != null) {
                // Specify class loader
                URLClassLoader loader = new URLClassLoader(new URL[]{new File(jar).toURI().toURL()}, TerminalManager.class.getClassLoader());
                // Load custom provider
                cls = Class.forName(classname, true, loader);
            } else {
                // Load provider
                cls = Class.forName(classname);
            }
            Provider p = (Provider) cls.getConstructor().newInstance();
            tf = TerminalFactory.getInstance(type == null ? "PC/SC" : type, arg, p);
            return tf;
        } catch (UnsupportedEncodingException | MalformedURLException | ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
            throw new NoSuchAlgorithmException("Could not load " + classname + ": " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            if (e.getCause() != null) {
                Class<?> cause = e.getCause().getClass();
                if (cause.equals(java.lang.UnsupportedOperationException.class)) {
                    throw new NoSuchAlgorithmException(e.getCause().getMessage());
                }
                if (cause.equals(java.lang.UnsatisfiedLinkError.class)) {
                    throw new NoSuchAlgorithmException(e.getCause().getMessage());
                }
            }
            throw e;
        }
    }


    /**
     * Given a specification for a TerminalFactory, returns a TerminalFactory instance.
     * <p>
     * The format is: jar:class:args, some heuristics is made to make the function DWIM.
     *
     * @param spec provider specification
     * @return properly loaded TerminalFactory from the provider
     * @throws NoSuchAlgorithmException if the provider can not be loaded for some reason
     */
    // FIXME: use ServiceLoader
    public static TerminalFactory getTerminalFactory(String spec) throws NoSuchAlgorithmException {
        // Default to bundled JNA by default.
        if (spec == null) {
            spec = JNA_CLASS;
        }
        // Always set the right path to pcsc
        fixPlatformPaths();

        // Split by colon marks
        String[] args = spec.split(":");
        if (args.length == 1) {
            // Assumed to be just the class
            return loadTerminalFactory(null, args[0], null, null);
        } else if (args.length == 2) {
            Path jarfile = Paths.get(args[0]);
            // If the first component is a valid file, assume provider!class
            if (Files.exists(jarfile)) {
                return loadTerminalFactory(args[0], args[1], null, null);
            } else {
                // Assume the first part is class and the second part is parameter
                return loadTerminalFactory(null, args[0], null, args[1]);
            }
        } else if (args.length == 3) {
            // jar:class:args
            return loadTerminalFactory(args[0], args[1], null, args[2]);
        } else {
            throw new IllegalArgumentException("Could not parse (too many components): " + spec);
        }
    }


    /**
     * Returns a card reader that has a card in it.
     * Asks for card insertion, if the system only has a single reader.
     *
     * @return a CardTerminal containing a card
     * @throws CardException if no suitable reader is found.
     * @deprecated since v0.0.33, use {@link #getTheReader(String)} instead
     */
    @Deprecated
    public static CardTerminal getTheReader() throws CardException {
        return getTheReader(JNA_CLASS);
    }

    /**
     * Returns a card reader that has a card in it.
     * Asks for card insertion, if the system only has a single reader.
     *
     * @return a CardTerminal containing a card
     * @throws CardException if no suitable reader is found.
     */
    public static CardTerminal getTheReader(String spec) throws CardException {
        try {
            String msg = "This application expects one and only one card reader (with an inserted card)";
            TerminalFactory tf = getTerminalFactory(spec);
            CardTerminals tl = tf.terminals();
            List<CardTerminal> list = tl.list(State.CARD_PRESENT);
            if (list.size() > 1) {
                throw new CardException(msg);
            } else if (list.size() == 1) {
                return list.get(0);
            } else {
                List<CardTerminal> wl = tl.list(State.ALL);
                // FIXME: JNA-s CardTerminals.waitForChange() does not work
                if (wl.size() == 1) {
                    CardTerminal t = wl.get(0);
                    System.out.println("Waiting for a card insertion to " + t.getName());
                    if (t.waitForCardPresent(0)) {
                        return t;
                    } else {
                        throw new CardException("Could not find a reader with a card");
                    }
                } else {
                    throw new CardException(msg);
                }
            }
        } catch (NoSuchAlgorithmException e) {
            throw new CardException(e);
        }
    }

    public static CardTerminal getByATR(Collection<byte[]> atrs) throws NoSuchAlgorithmException, CardException {
        TerminalFactory tf = TerminalFactory.getInstance("PC/SC", null, new jnasmartcardio.Smartcardio());
        CardTerminals ts = tf.terminals();
        List<CardTerminal> tl = ts.list(State.ALL);
        // If all are empty, wait for an entry.
        for (CardTerminal t : tl) {
            logger.trace("Checking {}", t.getName());
            if (t.isCardPresent()) {
                Card c = t.connect("DIRECT");
                byte[] cardatr = c.getATR().getBytes();
                c.disconnect(false);

                for (byte[] atr : atrs) {
                    if (Arrays.equals(cardatr, atr)) {
                        logger.debug("{} matched for ATR {}", t.getName(), HexUtils.bin2hex(atr));
                        return t;
                    }
                }
            }
        }
        throw new CardNotPresentException("No card with requested ATR present");
    }

    public static CardTerminal getByAID(Collection<byte[]> aids) throws NoSuchAlgorithmException, CardException {
        TerminalFactory tf = TerminalFactory.getInstance("PC/SC", null, new jnasmartcardio.Smartcardio());
        CardTerminals ts = tf.terminals();
        List<CardTerminal> tl = ts.list(State.ALL);
        for (CardTerminal t : tl) {
            if (t.isCardPresent()) {
                Card c = null;
                try {
                    c = t.connect("*");
                    for (byte[] aid : aids) {
                        // Try to select the AID
                        CommandAPDU s = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, aid, 256);
                        ResponseAPDU r = c.getBasicChannel().transmit(s);
                        if (r.getSW() == 0x9000) {
                            logger.debug("{} matched for AID {}", t.getName(), HexUtils.bin2hex(aid));
                            return t;
                        }
                    }
                } catch (CardException e) {
                    logger.trace("Could not connect or select AID", e);
                    continue;
                } finally {
                    if (c != null)
                        c.disconnect(false);
                }
            }
        }
        throw new CardNotPresentException("No card with requested AID present");
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
}
