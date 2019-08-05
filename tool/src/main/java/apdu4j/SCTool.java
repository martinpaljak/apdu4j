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

import apdu4j.i.BIBOProvider;
import apdu4j.i.BIBOServiceProvider;
import apdu4j.p.CardTerminalServiceProvider;
import apdu4j.terminals.LoggingCardTerminal;
import jnasmartcardio.Smartcardio;
import jnasmartcardio.Smartcardio.EstablishContextException;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import javax.smartcardio.*;
import javax.smartcardio.CardTerminals.State;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SCTool {
    private static final String OPT_LIST = "list";
    private static final String OPT_READER = "reader";
    private static final String OPT_WAIT = "wait";

    private static final String OPT_APDU = "apdu";

    private static final String OPT_SERVICE = "service";
    private static final String OPT_TOUCH_SERVICE = "touch-service";

    private static final String OPT_VERBOSE = "verbose";
    private static final String OPT_DEBUG = "debug";
    private static final String OPT_VERSION = "version";
    private static final String OPT_ERROR = "error";
    private static final String OPT_FORCE = "force";

    private static final String OPT_DUMP = "dump";
    private static final String OPT_REPLAY = "replay";

    private static final String OPT_HELP = "help";
    private static final String OPT_SUN = "sun";
    private static final String OPT_T0 = "t0";
    private static final String OPT_T1 = "t1";
    private static final String OPT_EXCLUSIVE = "exclusive";

    private static final String OPT_NO_GET_RESPONSE = "no-get-response";
    private static final String OPT_LIB = "lib";
    private static final String OPT_PROVIDERS = "P";

    private static boolean verbose = false;

    private static OptionSet parseOptions(String[] argv) throws IOException {
        OptionSet args = null;
        OptionParser parser = new OptionParser();
        parser.acceptsAll(Arrays.asList("l", OPT_LIST), "list readers");
        parser.acceptsAll(Arrays.asList("v", OPT_VERBOSE), "be verbose");
        parser.acceptsAll(Arrays.asList("f", OPT_FORCE), "use force");
        parser.acceptsAll(Arrays.asList("d", OPT_DEBUG), "show debug");
        parser.acceptsAll(Arrays.asList("e", OPT_ERROR), "fail if not 0x9000");
        parser.acceptsAll(Arrays.asList("h", OPT_HELP), "show help");
        parser.acceptsAll(Arrays.asList("r", OPT_READER), "use reader").withRequiredArg().describedAs("Reader name or plugin file");
        parser.acceptsAll(Arrays.asList("a", OPT_APDU), "send APDU").requiredIf(OPT_APDU).withRequiredArg().describedAs("HEX");
        parser.acceptsAll(Arrays.asList("w", OPT_WAIT), "wait for card insertion");
        parser.acceptsAll(Arrays.asList("V", OPT_VERSION), "show version information");
        parser.acceptsAll(Arrays.asList("s", OPT_SERVICE), "run service").withRequiredArg().describedAs("Service name or plugin file");
        parser.acceptsAll(Arrays.asList("S", OPT_TOUCH_SERVICE), "run service repeatedly").withRequiredArg().describedAs("Service name or plugin file");

        // TODO: move to plugin
        parser.accepts(OPT_DUMP, "save dump to file").withRequiredArg().ofType(File.class);
        parser.accepts(OPT_REPLAY, "replay command from dump").withRequiredArg().ofType(File.class);

        parser.accepts(OPT_SUN, "load SunPCSC");

        parser.accepts(OPT_PROVIDERS, "list providers");
        parser.accepts(OPT_T0, "use T=0");
        parser.accepts(OPT_T1, "use T=1");
        parser.accepts(OPT_EXCLUSIVE, "use EXCLUSIVE mode (JNA only)");
        parser.accepts(OPT_NO_GET_RESPONSE, "don't use GET RESPONSE with SunPCSC");
        parser.accepts(OPT_LIB, "use specific PC/SC lib with SunPCSC").withRequiredArg().describedAs("path");

        // Parse arguments
        try {
            args = parser.parse(argv);
            // Try to fetch all values so that format is checked before usage
            for (String s : parser.recognizedOptions().keySet()) {
                args.valuesOf(s);
            }
        } catch (OptionException e) {
            if (e.getCause() != null) {
                System.err.println(e.getMessage() + ": " + e.getCause().getMessage());
            } else {
                System.err.println(e.getMessage());
            }
            System.err.println();
            help_and_exit(parser, System.err);
        }
        if (args.has(OPT_HELP)) {
            help_and_exit(parser, System.out);
        }
        return args;
    }

    @SuppressWarnings("deprecation") // Provider.getVersion()
    public static void main(String[] argv) throws Exception {
        OptionSet args = parseOptions(argv);

        // Set up slf4j simple in a way that pleases us
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");
        // Set logging before calling Plug (which uses logging)
        if (args.has(OPT_VERBOSE)) {
            verbose = true;
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            if (args.has(OPT_DEBUG)) {
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace");
            }
        } else {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }

        // Verifies the signature
        System.setSecurityManager(new Plug());

        if (args.has(OPT_VERSION)) {
            String version = "apdu4j " + getVersion();
            // Append host information
            version += "\nRunning on " + System.getProperty("os.name");
            version += " " + System.getProperty("os.version");
            version += " " + System.getProperty("os.arch");
            version += ", Java " + System.getProperty("java.version");
            version += " by " + System.getProperty("java.vendor");
            System.out.println(version);
            System.out.println("Valid until " + Plug.getExpirationDate());
        }

        // We always bundle JNA with the tool, so add it to the providers.
        Security.addProvider(new jnasmartcardio.Smartcardio());

        try {
            // List TerminalFactory providers
            if (args.has(OPT_PROVIDERS)) {
                Provider providers[] = Security.getProviders("TerminalFactory.PC/SC");
                System.out.println("Existing TerminalFactory providers:");
                if (providers != null) {
                    for (Provider p : providers) {
                        System.out.printf("%s v%s (%s) from %s%n", p.getName(), p.getVersion(), p.getInfo(), Plug.pluginfile(p));
                    }
                }
                // List all plugins
                for (Class<?> p : Arrays.asList(BIBOProvider.class, BIBOServiceProvider.class, CardTerminalServiceProvider.class)) {
                    System.out.printf("Plugins for %s%n", p.getCanonicalName());
                    Plug.listPlugins(p);
                }
            }

            // Fix (SunPCSC) properties on non-windows platforms
            TerminalManager.fixPlatformPaths();

            // Don't issue APDU-s internally
            if (args.has(OPT_NO_GET_RESPONSE)) {
                System.setProperty("sun.security.smartcardio.t0GetResponse", "false");
                System.setProperty("sun.security.smartcardio.t1GetResponse", "false");
                System.setProperty("jnasmartcardio.transparent", "true");
            }

            // Override PC/SC library path (Only applies to SunPCSC)
            if (args.has(OPT_LIB)) {
                System.setProperty("sun.security.smartcardio.library", (String) args.valueOf(OPT_LIB));
            }

            final TerminalFactory tf;
            CardTerminals terminals = null;

            try {
                if (args.has(OPT_SUN)) {
                    tf = TerminalFactory.getDefault();
                } else {
                    tf = TerminalManager.getTerminalFactory();
                }

                if (verbose) {
                    System.out.println("# Using " + tf.getProvider().getClass().getCanonicalName() + " - " + tf.getProvider());
                    if (System.getProperty(TerminalManager.LIB_PROP) != null) {
                        System.out.println("# " + TerminalManager.LIB_PROP + "=" + System.getProperty(TerminalManager.LIB_PROP));
                    }
                }
                // Get all terminals
                terminals = tf.terminals();
            } catch (EstablishContextException e) {
                String msg = TerminalManager.getExceptionMessage(e);
                fail("No readers: " + msg);
            }

            // Terminals to work on
            CardTerminal reader = null;

            try {
                // List terminals
                if (args.has(OPT_LIST)) {
                    List<CardTerminal> terms = terminals.list();
                    if (verbose) {
                        System.out.println("# Found " + terms.size() + " terminal" + (terms.size() == 1 ? "" : "s"));
                    }
                    if (terms.size() == 0) {
                        fail("No readers found");
                    }
                    for (CardTerminal t : terms) {
                        String vmd = " ";
                        if (verbose) {
                            try (PinPadTerminal pp = PinPadTerminal.getInstance(t)) {
                                pp.probe();
                                // Verify, Modify, Display
                                vmd += "[";
                                vmd += pp.canVerify() ? "V" : " ";
                                vmd += pp.canModify() ? "M" : " ";
                                vmd += pp.hasDisplay() ? "D" : " ";
                                vmd += "] ";
                            } catch (CardException e) {
                                String err = TerminalManager.getExceptionMessage(e);
                                if (err.equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                                    vmd = " [   ] ";
                                } else
                                    vmd = " [EEE] ";
                            }
                        }
                        String present = t.isCardPresent() ? "[*]" : "[ ]";
                        String secondline = null;
                        String thirdline = null;
                        String filler = "          ";
                        if (args.has(OPT_VERBOSE) && t.isCardPresent()) {
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
                                    present = "[X]";
                                    try {
                                        c = t.connect("DIRECT");
                                        atr = c.getATR().getBytes();
                                    } catch (CardException e2) {
                                        String err2 = TerminalManager.getExceptionMessage(e2);
                                        if (err2.equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                                            present = "[X]";
                                        }
                                    }
                                } else {
                                    secondline = err;
                                }
                            } finally {
                                if (c != null)
                                    c.disconnect(false);
                            }

                            if (atr != null) {
                                secondline = HexUtils.bin2hex(atr).toUpperCase();
                                if (ATRList.locate().isPresent() || System.getenv().containsKey("SMARTCARD_LIST")) {
                                    final ATRList atrList;
                                    if (System.getenv().containsKey("SMARTCARD_LIST")) {
                                        atrList = ATRList.from(System.getenv("SMARTCARD_LIST"));
                                    } else {
                                        atrList = ATRList.from(ATRList.locate().get());
                                    }
                                    Optional<Map.Entry<String, String>> desc = atrList.match(atr);
                                    if (desc.isPresent()) {
                                        thirdline = atrList.match(atr).get().getValue().replace("\n", filler);
                                    }
                                } else {
                                    thirdline = "https://smartcard-atr.appspot.com/parse?ATR=" + HexUtils.bin2hex(atr);
                                }
                            }
                        }

                        System.out.println(present + vmd + t.getName());
                        if (secondline != null)
                            System.out.println(filler + secondline);
                        if (thirdline != null)
                            System.out.println(filler + thirdline);
                    }
                }

                // Select terminal to work on
                if (args.has(OPT_READER)) {
                    // TODO: default to fancy chooser, and support plugin-bibo
                    String rdr = (String) args.valueOf(OPT_READER);
                    CardTerminal t = terminals.getTerminal(rdr);
                    if (t == null) {
                        fail("Reader \"" + rdr + "\" not found.");
                    }
                    reader = t;
                } else {
                    if (terminals.list(State.CARD_PRESENT).size() == 0 && !args.has(OPT_LIST)) {
                        // But if there is a single reader, wait for a card insertion
                        List<CardTerminal> empty = terminals.list(State.CARD_ABSENT);
                        if (empty.size() == 1 && args.has(OPT_WAIT)) {
                            CardTerminal rdr = empty.get(0);
                            System.out.println("Please enter a card into " + rdr.getName());
                            if (!empty.get(0).waitForCardPresent(30000)) {
                                System.out.println("Timeout.");
                            } else {
                                reader = rdr;
                            }
                        } else {
                            fail("No reader with a card found!");
                        }
                    }
                }
            } catch (CardException e) {
                // Address Windows with SunPCSC
                String em = TerminalManager.getExceptionMessage(e);
                if (em.equals(SCard.SCARD_E_NO_READERS_AVAILABLE)) {
                    fail("No reader with a card found!");
                } else {
                    System.err.println("Could not list readers: " + em);
                }
            }

            if (args.has(OPT_VERBOSE)) {
                FileOutputStream o = null;
                if (args.has(OPT_DUMP)) {
                    try {
                        o = new FileOutputStream((File) args.valueOf(OPT_DUMP));
                    } catch (FileNotFoundException e) {
                        System.err.println("Can not dump to " + args.valueOf(OPT_DUMP));
                    }
                }
                reader = LoggingCardTerminal.getInstance(reader, o);
            }

            // If we have meaningful work
            if (!(args.has(OPT_SERVICE) || args.has(OPT_APDU) || args.has(OPT_TOUCH_SERVICE)))
                System.exit(0);

            // Do it
            work(reader, args);
        } catch (CardException e) {
            if (TerminalManager.getExceptionMessage(e) != null) {
                System.out.println("PC/SC failure: " + TerminalManager.getExceptionMessage(e));
            } else {
                System.out.printf("%s: %s", e.getClass().getName(), e.getMessage());
            }
        } catch (Exception e) {
            System.out.printf("%s: %s", e.getClass().getName(), e.getMessage());
            if (verbose)
                e.printStackTrace();
            System.exit(1);
        }
    }


    static String getProtocol(OptionSet args) {
        String protocol;
        if (args.has(OPT_T0)) {
            protocol = "T=0";
        } else if (args.has(OPT_T1)) {
            protocol = "T=1";
        } else {
            protocol = "*";
        }
        // JNA-proprietary
        if (args.has(OPT_EXCLUSIVE)) {
            protocol = "EXCLUSIVE;" + protocol;
        } else if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            // Windows 8+ have the "5 seconds of transaction" limit. Because we want reliability
            // and don't have access to arbitrary SCard* calls via javax.smartcardio, we rely on
            // JNA interface and its EXCLUSIVE access instead and do NOT use the SCardBeginTransaction
            // capability of the JNA interface.
            // https://msdn.microsoft.com/en-us/library/windows/desktop/aa379469%28v=vs.85%29.aspx
            protocol = "EXCLUSIVE;" + protocol;
        }
        return protocol;
    }

    private static void waitForCardOrExit(CardTerminal t) throws CardException {
        boolean found = false;
        System.err.print("Waiting for card ...");
        for (int i = 20; i > 0 && !found; i--) {
            found = t.waitForCardPresent(3000); // Wait for a minute in 3 second rounds
            System.err.print(".");
        }
        System.err.println();
        if (!found) {
            System.err.println("Timeout, bye!");
            System.exit(0);
        }
    }

    private static void repeat(CardTerminal reader, OptionSet args, BIBOServiceProvider srvc) throws CardException {
        Thread cleanup = new Thread(() -> {
            System.err.println("\napdu4j is done");
        });
        Runtime.getRuntime().addShutdownHook(cleanup);

        try {
            while (true) {
                waitForCardOrExit(reader);
                final Card card;
                try {
                    card = reader.connect(getProtocol(args));
                } catch (CardException e) {
                    System.err.println("W: Too fast, try again!");
                    Thread.sleep(300); // to avoid instant re-powering
                    continue;
                }
                APDUBIBO bibo = CardChannelBIBO.getBIBO(card.getBasicChannel());
                // Run service
                srvc.get().apply(bibo);
                card.disconnect(true);

                int i = 0;
                boolean removed;
                // Wait until card is removed
                do {
                    removed = reader.waitForCardAbsent(1000);
                    if (i >= 10 && i % 10 == 0) {
                        System.err.println("W: Remove card!");
                    }
                } while (removed == false && i++ < 60);
                // Final check. If card has not been removed, fail
                if (!removed) {
                    fail("E: Stuck card detected: " + reader.getName());
                }
                Thread.sleep(300); // to avoid re-powering
            }
        } catch (InterruptedException e) {
            System.err.println("Interrupted: " + e.getMessage());
        }
    }

    private static void work(CardTerminal reader, OptionSet args) throws CardException {
        // TODO: must be handled before calling this.
        if (!reader.isCardPresent()) {
            fail("No card in " + reader.getName());
        }
        if (args.has(OPT_TOUCH_SERVICE)) {
            Optional<BIBOServiceProvider> biboService = Plug.getPlugin(args.valueOf(OPT_TOUCH_SERVICE).toString(), BIBOServiceProvider.class);
            if (biboService.isPresent()) {
                repeat(reader, args, biboService.get());
            }
        } else {
            Card c = null;
            try {
                c = reader.connect(getProtocol(args));
                if (args.has(OPT_APDU)) {
                    for (Object s : args.valuesOf(OPT_APDU)) {
                        javax.smartcardio.CommandAPDU a = new javax.smartcardio.CommandAPDU(HexUtils.stringToBin((String) s));
                        javax.smartcardio.ResponseAPDU r = c.getBasicChannel().transmit(a);
                        if (args.has(OPT_ERROR) && r.getSW() != 0x9000) {
                            fail("Card returned " + String.format("%04X", r.getSW()) + ", exiting!");
                        }
                    }
                }
                if (args.has(OPT_SERVICE)) {
                    // Locate plugin. First try terminal plugin
                    Optional<CardTerminalServiceProvider> pcscService = Plug.getPlugin(args.valueOf(OPT_SERVICE).toString(), CardTerminalServiceProvider.class);
                    if (pcscService.isPresent()) {
                        System.exit(pcscService.get().get().apply(reader) ? 0 : 1);
                    }
                    // Then arbitrary BIBO
                    Optional<BIBOServiceProvider> biboService = Plug.getPlugin(args.valueOf(OPT_SERVICE).toString(), BIBOServiceProvider.class);
                    if (biboService.isPresent()) {
                        System.exit(biboService.get().get().apply(CardChannelBIBO.getBIBO(c.getBasicChannel())) ? 0 : 1);
                    }
                }
            } finally {
                if (c != null) {
                    c.disconnect(true);
                }
            }
        }
    }

    private static void help_and_exit(OptionParser parser, PrintStream o) throws IOException {
        System.err.println("# apdu4j command line utility");
        System.err.println();
        parser.printHelpOn(o);
        System.exit(1);
    }

    public static String getVersion() {
        String version = "unknown-development";
        try (InputStream versionfile = SCTool.class.getResourceAsStream("pro_version.txt")) {
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

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
