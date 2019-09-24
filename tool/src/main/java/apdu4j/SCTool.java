/*
 * Copyright (c) 2014-2020 Martin Paljak
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
import apdu4j.i.SmartCardApp;
import apdu4j.i.TouchTerminalApp;
import apdu4j.p.CardTerminalApp;
import apdu4j.p.CardTerminalProvider;
import apdu4j.p.TouchTerminalRunner;
import apdu4j.terminals.LoggingCardTerminal;
import jnasmartcardio.Smartcardio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import javax.smartcardio.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.Security;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(name = "apdu4j", versionProvider = SCTool.class, mixinStandardHelpOptions = true, subcommands = {HelpCommand.class})
public class SCTool implements Callable<Integer>, IVersionProvider {
    final Logger logger = LoggerFactory.getLogger(SCTool.class);
    private TerminalFactory tf = null;
    @Option(names = {"-v", "--verbose"}, description = "Be verbose")
    boolean verbose;
    @Option(names = {"-d", "--debug"}, description = "Trace APDU-s")
    boolean debug;
    @Option(names = {"-f", "--force"}, description = "Force, don't stop on errors")
    boolean force;
    @Option(names = {"-l", "--list"}, description = "List readers")
    boolean list;
    @Option(names = {"-P", "--plugins"}, description = "List plugins")
    boolean listPlugins;
    @Option(names = {"-W", "--no-wait"}, description = "Don't wait for card before running app")
    boolean noWait;
    @Option(names = {"-B", "--bare-bibo"}, description = "Don't handle 61XX/6CXX")
    boolean bareBibo;
    @Option(names = {"-r", "--reader"}, arity = "0..1", description = "Use reader", paramLabel = "<reader>", fallbackValue = "")
    String reader;
    @Option(names = {"-a", "--apdu"}, description = "Send APDU-s", paramLabel = "<HEX>")
    byte[][] apdu = {};

    @Parameters
    String[] params = {};

    @ArgGroup(heading = "Protocol selection (default is T=*)%n")
    T0T1 proto = new T0T1();

    static class T0T1 {
        @Option(names = {"--t0"}, description = "Use T=0")
        boolean t0;
        @Option(names = {"--t1"}, description = "Use T=1")
        boolean t1;
    }

    @ArgGroup(heading = "Low level options%n", validate = false)
    LowLevel lowlevel = new LowLevel();

    static class LowLevel {
        @Option(names = {"-X", "--exclusive"}, description = "Use EXCLUSIVE mode (JNA only)")
        boolean exclusive;
        @Option(names = {"-S", "--sun"}, description = "Use SunPCSC instead of JNA", arity = "0..1", paramLabel = "<lib>", fallbackValue = "")
        String useSUN;
    }

    private void verbose(String s) {
        if (verbose)
            System.out.println("# " + s);
    }

    @Command(name = "list", description = "List available smart card readers.", aliases = {"ls"})
    public int listReaders(@Option(names = {"-v", "--verbose"}) boolean verbose) {
        final ReaderAliases aliases = ReaderAliases.getDefault();
        try {
            List<CardTerminal> terms = getTerminalFactory().terminals().list();
            verbose(String.format("Found %d reader%s", terms.size(), terms.size() == 1 ? "" : "s"));
            if (terms.size() == 0) {
                fail("No readers found");
            }
            // TODO: consolidate connects, so that logging would not interleave listing
            for (CardTerminal t : terms) {
                if (debug)
                    t = LoggingCardTerminal.getInstance(t);
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
                            vmd = " [???] ";
                        } else
                            vmd = " [EEE] ";
                    }
                }
                String present = t.isCardPresent() ? "[*]" : "[ ]";
                String secondline = null;
                String thirdline = null;
                String filler = "          ";
                if (verbose && t.isCardPresent()) {
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
                            thirdline = "https://smartcard-atr.apdu.fr/parse?ATR=" + HexUtils.bin2hex(atr);
                        }
                    }
                }

                final String name = aliases.extended(t.getName());
                System.out.println(present + vmd + name);
                if (secondline != null)
                    System.out.println(filler + secondline);
                if (thirdline != null)
                    System.out.println(filler + thirdline);
            }
        } catch (CardException | IOException e) {
            fail("Failed: " + e.getMessage());
            // Address Windows with SunPCSC
            String em = TerminalManager.getExceptionMessage(e);
            if (em.equals(SCard.SCARD_E_NO_READERS_AVAILABLE)) {
                fail("No reader with a card found!");
            } else {
                fail("Could not list readers: " + em);
            }
        }
        return 0;
    }

    @Command(name = "run", description = "Run specified smart card application")
    public int runApp(@Parameters(paramLabel = "<app>", index = "0") String app, @Parameters(index = "1..*") String[] args) {
        try {
            System.out.println("running app: " + app);
            System.out.println("running with args: " + Arrays.toString(args));

            // Resolve the app
            if (args == null)
                args = new String[0];
            if (!resolveApp(app)) {
                System.err.println("App not found: " + app);
                return 66;
            }
            Optional<CardTerminal> rdr = getTheTerminal(reader);
            if (!rdr.isPresent()) {
                fail("Specify valid reader to use with -r");
            } else {
                logger.info("Using " + rdr.get());
            }

            CardTerminal reader = rdr.get();

            // This sets the protocol property, also for CardTerminalApp and TouchTerminalApp
            getProtocol();

            // TODO: reverse order and resolve apps before running, to avoid plugin lookup warnings
            // First a CardTerminalApp
            Optional<CardTerminalApp> terminalApp = Plug.getRemotePluginIfNotLocal(app, CardTerminalApp.class);
            if (terminalApp.isPresent()) {
                exiter();
                return terminalApp.get().run(reader, args);
            }

            // Then TouchTerminalApp
            Optional<TouchTerminalApp> touchApp = Plug.getRemotePluginIfNotLocal(app, TouchTerminalApp.class);
            if (touchApp.isPresent()) {
                exiter();
                return TouchTerminalRunner.run(reader, touchApp.get(), args);
            }

            // Then SmartCardApp
            Optional<SmartCardApp> biboApp = Plug.getRemotePluginIfNotLocal(app, SmartCardApp.class);
            if (biboApp.isPresent()) {
                try (BIBO bibo = getBIBO(rdr)) {
                    APDUBIBO ar = new APDUBIBO(bibo);
                    // This allows to send "initialization" APDU-s before an app
                    if (apdu != null) {
                        for (byte[] s : apdu) {
                            CommandAPDU a = new CommandAPDU(s);
                            ResponseAPDU r = ar.transmit(a);
                            if (r.getSW() != 0x9000 && !force) {
                                fail("Card returned " + String.format("%04X", r.getSW()) + ", exiting!");
                            }
                        }
                    }
                    // Then run the app
                    exiter();
                    return biboApp.get().run(bibo, args);
                }
            }
        } catch (CardException e) {
            logger.error("Card failed: " + e.getMessage(), e);
            System.err.println("Failed: " + e.getMessage());
        } catch (RuntimeException e) {
            logger.error("App failed: " + e.getMessage(), e);
        }
        return 66;
    }

    void exiter() {
        if (verbose)
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("\n\nYou were using apdu4j. Cool!")));
    }

    @Command(name = "apdu", description = "Send raw APDU-s (Bytes Out)")
    public int sendAPDU(@Parameters(paramLabel = "<hex>", arity = "1..*") List<byte[]> apdus) {
        // Prepend the -a ones
        List<byte[]> toCard = new ArrayList<>(Arrays.asList(apdu));
        toCard.addAll(apdus);
        try (APDUBIBO b = new APDUBIBO(getBIBO(getTheTerminal(reader)))) {
            for (byte[] s : toCard) {
                ResponseAPDU r = b.transmit(new CommandAPDU(s));
                if (r.getSW() != 0x9000 && !force) {
                    fail("Card returned " + String.format("%04X", r.getSW()) + ", exiting!");
                }
            }
        } catch (CardException e) {
            logger.error("Could not connect: " + e.getMessage(), e);
            return 1;
        } catch (BIBOException e) {
            logger.error("Failed: " + e.getMessage(), e);
            return 1;
        }
        return 0;
    }


    @Command(name = "plugins", description = "List available plugins.")
    @SuppressWarnings("deprecation")
    // Provider.getVersion()
    public int listPlugins() {
        // List TerminalFactory providers
        Provider providers[] = Security.getProviders("TerminalFactory.PC/SC");
        System.out.println("Existing TerminalFactory providers:");
        if (providers != null) {
            for (Provider p : providers) {
                System.out.printf("%s v%s (%s) from %s%n", p.getName(), p.getVersion(), p.getInfo(), Plug.pluginfile(p));
            }
        }
        // List all plugins
        for (Class<?> p : Arrays.asList(BIBOProvider.class, CardTerminalProvider.class)) {
            System.out.printf("Plugins for %s%n", p.getCanonicalName());
            Plug.listPlugins(p);
        }

        // List all apps
        for (Class<?> p : Arrays.asList(SmartCardApp.class, TouchTerminalApp.class, CardTerminalApp.class)) {
            System.out.printf("Apps for %s%n", p.getCanonicalName());
            Plug.listPlugins(p);
        }
        return 1;
    }


    static void configureLogging() {
        // Set up slf4j simple in a way that pleases us
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");

        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss:SSS");

        // Default level
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    public static void main(String[] args) {
        try {
            // Configure logging
            configureLogging();
            // We always bundle JNA with the tool, so add it to the providers as well
            Security.addProvider(new jnasmartcardio.Smartcardio());
            // Parse CLI
            SCTool tool = new SCTool();
            CommandLine cli = new CommandLine(tool);
            // To support "sc gp -ldv"
            cli.setUnmatchedOptionsArePositionalParams(true);
            //cli.setStopAtUnmatched(true);
            cli.setStopAtPositional(true);
            cli.registerConverter(byte[].class, s -> HexUtils.stringToBin(s));
            try {
                cli.parseArgs(args);
                if (tool.debug)
                    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            } catch (ParameterException ex) { // command line arguments could not be parsed
                System.err.println(ex.getMessage());
                ex.getCommandLine().usage(System.err);
                System.exit(1);
            }

            // Run program
            cli.execute(args);
        } catch (RuntimeException e) {
            fail("Error: " + e.getMessage());
        }
    }


    @Override
    public Integer call() {
        // Old style shorthands
        if (listPlugins)
            return listPlugins();
        if (list)
            return listReaders(verbose);
        if (apdu.length > 0) {
            return sendAPDU(Arrays.asList(apdu));
        }
        // Default is to run apps
        if (params.length > 0) {
            if (resolveApp(params[0])) {
                return runApp(params[0], Arrays.copyOfRange(params, 1, params.length));
            } else {
                System.err.println("Unknown app: " + params[0]);
                return 66;
            }
        }
        System.out.println("Nothing to do!");
        return 0;
    }

    Optional<String> forceLibraryPath() {
        if (lowlevel.useSUN != null && lowlevel.useSUN.trim().length() > 0) {
            return Optional.of(lowlevel.useSUN.trim());
        }
        return Optional.empty();
    }


    // Return a terminal factory, taking into account CLI options
    TerminalFactory getTerminalFactory() {
        if (tf != null)
            return tf;
        // Separate trycatch block for potential Windows exception in terminal listing
        try {
            if (lowlevel.useSUN != null) {
                // Fix (SunPCSC) properties on non-windows platforms
                TerminalManager.fixPlatformPaths();

                // Override PC/SC library path (Only applies to SunPCSC)
                forceLibraryPath().ifPresent(e -> System.setProperty(TerminalManager.LIB_PROP, e));

                // Log the library if verbose
                if (verbose && System.getProperty(TerminalManager.LIB_PROP) != null) {
                    System.out.println("# " + TerminalManager.LIB_PROP + "=" + System.getProperty(TerminalManager.LIB_PROP));
                }
                // Get the built-int provider
                tf = TerminalFactory.getDefault();
            } else {
                // Get JNA
                tf = TerminalManager.getTerminalFactory();
            }

            if (verbose) {
                System.out.println("# Using " + tf.getProvider().getClass().getCanonicalName() + " - " + tf.getProvider());
            }
            return tf;
        } catch (Smartcardio.EstablishContextException e) {
            String msg = TerminalManager.getExceptionMessage(e);
            fail("No readers: " + msg);
            return null; // sugar.
        }
    }


    boolean weHaveApp(String spec) {
        return Stream.concat(Plug.pluginStream(SmartCardApp.class), Plug.pluginStream(CardTerminalApp.class)).anyMatch(e -> Plug.identifies(spec, e.getClass()));
    }

    boolean resolveApp(String spec) {
        return Plug.getRemotePluginIfNotLocal(spec, SmartCardApp.class).isPresent()
                || Plug.getRemotePluginIfNotLocal(spec, CardTerminalApp.class).isPresent()
                || Plug.getRemotePluginIfNotLocal(spec, TouchTerminalApp.class).isPresent();
    }

    private String getProtocol() {
        String protocol;
        if (proto.t0) {
            protocol = "T=0";
        } else if (proto.t1) {
            protocol = "T=1";
        } else {
            protocol = "*";
        }

        // JNA-proprietary
        if (lowlevel.useSUN == null) {
            // Windows 8+ have the "5 seconds of transaction" limit. Because we want reliability
            // and don't have access to arbitrary SCard* calls via javax.smartcardio, we rely on
            // JNA interface and its EXCLUSIVE access instead and do NOT use the SCardBeginTransaction
            // capability of the JNA interface.
            // https://msdn.microsoft.com/en-us/library/windows/desktop/aa379469%28v=vs.85%29.aspx
            if (System.getProperty("os.name").toLowerCase().contains("windows") || lowlevel.exclusive)
                protocol = "EXCLUSIVE;" + protocol;
        }
        System.setProperty(CardTerminalApp.PROTOCOL_PROPERTY, protocol);
        return protocol;
    }

    private String envOrMeta(String n1) {
        if (System.getenv().containsKey(n1)) {
            String v1 = System.getenv(n1);
            if (v1.startsWith("$")) {
                String n2 = v1.substring(1);
                if (!System.getenv().containsKey(n2))
                    logger.warn("${} is not set", n2);
                return System.getenv(n2);
            } else
                return v1;
        }
        return null;
    }

    // Return a BIBO or fail
    private BIBO getBIBO(Optional<CardTerminal> rdr) throws CardException {
        if (!rdr.isPresent()) {
            fail("Specify valid reader to use with -r");
        } else {
            logger.info("Using " + rdr.get());
        }
        CardTerminal reader = rdr.get();
        if (!noWait && !reader.isCardPresent()) {
            boolean present = TouchTerminalRunner.waitForCard(reader, 60);
            if (!present) {
                fail("No card in reader. Quit.");
            }
        }
        Card c = reader.connect(getProtocol());
        final BIBO bibo;
        if (bareBibo) {
            bibo = CardBIBO.wrap(c);
        } else {
            bibo = GetResponseWrapper.wrap(CardBIBO.wrap(c));
        }
        return bibo;
    }

    // Return a terminal
    private Optional<CardTerminal> getTheTerminal(String spec) {
        // Don't issue APDU-s internally
        if (bareBibo) {
            System.setProperty("sun.security.smartcardio.t0GetResponse", "false");
            System.setProperty("sun.security.smartcardio.t1GetResponse", "false");
            System.setProperty("jnasmartcardio.transparent", "true");
        }

        Optional<CardTerminal> result = Optional.empty();
        if (spec == null) {
            // if APDU4J_READER present - use this, resolving plugins
            String APDU4J_READER = "APDU4J_READER";
            if (envOrMeta(APDU4J_READER) != null) {
                result = Plug.pluginStream(CardTerminalProvider.class) // stream of CTP
                        .map(e -> e.getTerminal(envOrMeta(APDU4J_READER))) // stream of optional<cardTerminal>
                        .filter(Optional::isPresent).map(Optional::get)
                        .findFirst();
                if (!result.isPresent()) {
                    logger.warn(String.format("$%s present but does not resolve to a reader", APDU4J_READER));
                }
            } else {
                // if only one reader - use this (after applying EXCLUDE)
                TerminalFactory tf = getTerminalFactory();
                CardTerminals terminals = tf.terminals();
                String ignoreList = System.getenv("APDU4J_READER_IGNORE");
                try {
                    List<CardTerminal> terms = terminals.list().stream().filter(e -> TerminalManager.ignoreReader(ignoreList, e.getName())).collect(Collectors.toList());
                    if (terms.size() == 1) {
                        result = Optional.of(terms.get(0));
                    } else {
                        // if more than on reader - use fancy chooser (possibly graying out exclude ones)
                        result = FancyChooser.forTerminals(getTerminalFactory().terminals()).call();
                    }
                } catch (CardException | IOException e) {
                    logger.error("Failed to list/choose readers: " + e.getMessage());
                }
            }
            // if chooser not possible - require usage of -r (return empty optional)
        } else {
            // if -r present but no value: force chooser
            if (reader.length() == 0) {
                try {
                    result = FancyChooser.forTerminals(getTerminalFactory().terminals()).call();
                } catch (IOException | CardException e) {
                    logger.error("Could not choose terminal: " + e.getMessage(), e);
                }
            } else {
                // -r present with a value: match plugins
                result = Plug.pluginStream(CardTerminalProvider.class) // stream of CTP
                        .map(e -> e.getTerminal(reader)) // stream of optional<cardTerminal>
                        .filter(Optional::isPresent).map(Optional::get)
                        .findFirst();
                if (!result.isPresent()) {
                    logger.warn(String.format("%s does not resolve to a reader", reader));
                }
            }
            // if no match, see if -r is a URL, then load plugin from there
        }
        result.ifPresent((t) -> System.out.println("# Using " + t.getName()));
        return result.map(t -> debug ? LoggingCardTerminal.getInstance(t) : t);
    }

    public String[] getVersion() {
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
        return new String[]{version};
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}