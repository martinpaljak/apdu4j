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
package apdu4j.tool;

import apdu4j.core.ResponseAPDU;
import apdu4j.core.*;
import apdu4j.pcsc.*;
import apdu4j.pcsc.terminals.LoggingCardTerminal;
import jnasmartcardio.Smartcardio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import javax.smartcardio.*;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Provider;
import java.security.Security;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "apdu4j", versionProvider = SCTool.class, mixinStandardHelpOptions = true, subcommands = {HelpCommand.class})
public class SCTool implements Callable<Integer>, IVersionProvider {
    public static final String ENV_APDU4J_APPS = "APDU4J_APPS";
    public static final String ENV_APDU4J_READER = "APDU4J_READER";
    public static final String ENV_APDU4J_READER_IGNORE = "APDU4J_READER_IGNORE";
    public static final String ENV_APDU4J_DEBUG = "APDU4J_DEBUG";
    public static final String ENV_APDU4J_DEBUG_FILE = "APDU4J_DEBUG_FILE";
    public static final String ENV_SMARTCARD_LIST = "SMARTCARD_LIST";


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
    @Option(names = {"-W", "--no-wait"}, description = "Don't wait for card before running app")
    boolean noWait;
    @Option(names = {"-B", "--bare-bibo"}, description = "Don't handle 61XX/6CXX")
    boolean bareBibo;
    @Option(names = {"-r", "--reader"}, description = "Use reader", paramLabel = "<reader>")
    String reader;
    @Option(names = {"-R"}, description = "Force reader selector")
    boolean forceReaderSelection;

    @Option(names = {"-a", "--apdu"}, description = "Send APDU-s", paramLabel = "<HEX>")
    byte[][] apdus = {};

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

    private Map<String, SmartCardApp> apps;

    private TerminalFactory factory;
    private CardTerminals terminals;

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

    void printReaderList(List<PCSCReader> readers, PrintStream to, boolean verbose) {
        if (readers.size() == 0) {
            to.println("No readers found");
            return;
        }
        ReaderAliases aliases = ReaderAliases.getDefault().apply(readers.stream().map(PCSCReader::getName).collect(Collectors.toList()));

        ATRList atrList = null;
        Optional<String> atrListPath = Optional.ofNullable(System.getenv(ENV_SMARTCARD_LIST)).or(ATRList::locate);
        boolean hasSomeATR = readers.stream().filter(PCSCReader::isPresent).count() > 0;
        if (atrListPath.isPresent() && hasSomeATR) {
            try {
                atrList = ATRList.from(atrListPath.get());
            } catch (IOException e) {
                logger.warn("Could not load ATR list: {}", e.getMessage(), e);
            }
        }
        int i = 0;
        String filler = readers.size() > 10 ? "              " : "             ";
        if (atrList != null && verbose)
            verbose("ATR info from " + atrList.getSource().orElse("unknown source"));
        for (PCSCReader r : readers) {
            i++;
            String vmdString = r.getVMD().map(e -> String.format("[%s] ", e)).orElse("");
            char marker = verbose ? PCSCReader.presenceMarker(r) : (r.isPresent() ? '*' : ' ');
            to.println(String.format("%d: [%c] %s%s", i, marker, vmdString, aliases.extended(r.getName())));
            if (verbose) {
                if (r.getATR().isPresent()) {
                    byte[] atr = r.getATR().get();
                    to.println(String.format("%s%s", filler, HexUtils.bin2hex(atr)));
                    if (atrList != null) {
                        Optional<Map.Entry<String, List<String>>> desc = atrList.match(atr);
                        if (desc.isPresent()) {
                            desc.get().getValue().stream().forEachOrdered(l -> to.printf("%s- %s%n", filler, l));
                        }
                    } else {
                        to.printf("%shttps://smartcard-atr.apdu.fr/parse?ATR=%s%n", filler, HexUtils.bin2hex(atr));
                    }
                }
            }
        }
    }

    @Command(name = "list", description = "List available smart card readers.", aliases = {"ls"})
    public int listReaders(@Option(names = {"-v", "--verbose"}) boolean verbose) {

        boolean beVerbose = this.verbose || verbose;
        if (beVerbose) {
            List<String> env = System.getenv().entrySet().stream().filter(e -> e.getKey().startsWith("APDU4J_")).map(e -> String.format("%s=\"%s\"", e.getKey(), e.getValue())).collect(Collectors.toList());
            if (env.size() > 0)
                verbose(String.join(" ", env));
        }
        try {
            List<PCSCReader> result = TerminalManager.listPCSC(getTerminalFactory().terminals().list(), debug ? System.out : null, beVerbose);
            TerminalManager.dwimify(result, System.getenv(ENV_APDU4J_READER), System.getenv(ENV_APDU4J_READER_IGNORE));
            printReaderList(result, System.out, beVerbose);
        } catch (Smartcardio.EstablishContextException | CardException e) {
            String em = SCard.getExceptionMessage(e);
            if (em.equals(SCard.SCARD_E_NO_SERVICE)) {
                return fail("PC/SC service is not running: " + em);
            } else if (em.equals(SCard.SCARD_E_NO_READERS_AVAILABLE)) {
                // Address Windows with SunPCSC
                return fail("No readers found: " + em);
            } else {
                return fail("Could not list readers: " + em);
            }
        }
        return 0;
    }

    // Allow overriding apps folder TODO: Windows %APPDATA%?
    static Path appsFolder = Paths.get(System.getenv().getOrDefault(ENV_APDU4J_APPS, Paths.get(System.getProperty("user.home", ""), ".apdu4j", "apps").toString()));

    @Command(name = "apps", description = "List available apps.")
    public int listApps() {
        if (apps == null)
            apps = resolveApps();

        // Apps are instances of SmartCardApp.
        if (!Files.isDirectory(appsFolder)) {
            fail("# Tip: create " + appsFolder + " and place there all your app jar-s");
        }

        List<Path> jarApps = Plug.jars(appsFolder);
        if (jarApps.size() == 0)
            fail("Tip: put all your apdu4j JAR apps into " + appsFolder);

        // Check all drop-in apps
        jarApps.forEach(p -> {
            if (Plug.loadPlugins(p, SmartCardApp.class).size() == 0) {
                verbose(String.format("%s does not contain a SmartCardApp, %n\tplease upgrade or remove the app file.%n", p));
            }
        });
        int maxNameLen = apps.keySet().stream().mapToInt(String::length).max().getAsInt();
        int maxClassLen = apps.values().stream().mapToInt(e -> e.getClass().getCanonicalName().length()).max().getAsInt();

        String format = String.format("%%-%ds   %%-%ds   (%%s)%%n", maxNameLen, maxClassLen);
        String headerFormat = String.format("%%-%ds   %%-%ds   %%s%%n", maxNameLen, maxClassLen);
        if (verbose)
            System.out.printf(headerFormat, "# Name", "Class", "From");
        for (Map.Entry<String, SmartCardApp> e : apps.entrySet()) {
            System.out.printf(format, e.getKey(), e.getValue().getClass().getCanonicalName(), Plug.pluginfile(e.getValue()));
        }
        return 0;
    }


    // Note: file is actually known to be a file end with .jar, see Plug::jars
    Optional<String> cmdname(Path p) {
        Path fileName = p.getFileName();
        if (fileName == null) return Optional.empty(); // To work around findbugs null check
        String fname = fileName.toString().toLowerCase();
        String extension = ".jar";
        if (fname.endsWith(extension)) {
            return Optional.of(fname.substring(0, fname.length() - extension.length()));
        }
        return Optional.empty();
    }

    Map<String, SmartCardApp> resolveApps() {
        TreeMap<String, SmartCardApp> allApps = new TreeMap<>();
        List<Path> jars = Plug.jars(appsFolder);
        // enumerate jar-s
        for (Path p : jars) {
            Optional<String> jarcmd = cmdname(p);
            List<SmartCardApp> apps = Plug.loadPlugins(p, SmartCardApp.class);
            apps.forEach(a -> {
                if (allApps.containsKey(a.getName()))
                    logger.info("{} already present via  {}", a.getName(), allApps.get(a.getName()));
                allApps.putIfAbsent(a.getName(), a);
            });
            // Rule 1: foo.jar in apps folder overrides getName() == foo
            if (apps.size() == 1 && jarcmd.isPresent()) {
                allApps.put(jarcmd.get(), apps.get(0));
            }
            if (apps.size() == 0) {
                logger.info("{} is not SmartCardApp", p);
            }
        }
        // Enumerate builtin apps
        ServiceLoader<SmartCardApp> sl1 = ServiceLoader.load(SmartCardApp.class);
        sl1.stream().forEach(a -> {
            if (allApps.containsKey(a.get().getName())) {
                logger.info("{} overrides builtin {}", allApps.get(a.get().getName()).getClass().getCanonicalName(), a.get().getName());
            }
            allApps.putIfAbsent(a.get().getName(), a.get());
        });

        return allApps;
    }

    @Command(name = "run", description = "Run specified smart card application")
    public int runApp(@Parameters(paramLabel = "<app>", index = "0") String app, @Parameters(index = "1..*") String[] args) {
        if (args == null)
            args = new String[0];

        if (apps == null)
            apps = resolveApps();

        try {
            // Resolve reader
            String preferredHint = reader == null ? System.getenv(ENV_APDU4J_READER) : reader;
            Optional<CardTerminal> rdr = getTheTerminal(preferredHint);

            rdr.ifPresent((t) -> verbose("Using " + t.getName()));

            if (rdr.isEmpty()) {
                return fail("Specify valid reader to use with -r");
            }

            // Resolve app
            List<Map.Entry<String, SmartCardApp>> matches = apps.entrySet().stream().filter(e -> e.getKey().equals(app)).collect(Collectors.toList());
            if (matches.size() == 0)
                matches = apps.entrySet().stream().filter(e -> e.getKey().startsWith(app)).collect(Collectors.toList());

            if (matches.size() == 0) {
                fail("App not found: " + app);
                return 66;
            } else if (matches.size() > 1) {
                matches.forEach(e -> System.err.println("   - " + e.getKey()));
                return fail("Multiple choices for " + app);
            } else {
                SmartCardApp sca = matches.get(0).getValue();
                verbose(String.format("Running %s (%s) from %s", sca.getName(), sca.getClass().getCanonicalName(), Plug.pluginfile(sca)));
                if (sca instanceof SimpleSmartCardApp) {
                    CardTerminal reader = rdr.get();
                    if (!reader.isCardPresent() && !noWait) {
                        System.out.println("# Waiting for card in " + reader.getName());
                        CardTerminalAppRunner.waitForCard(reader);
                    }
                    BIBO b = new BlockingBIBO(CardBIBO.wrap(reader.connect(getProtocol())));
                    // This allows to send "initialization" APDU-s before an app
                    if (apdus != null) {
                        for (byte[] s : apdus) {
                            ResponseAPDU r = new ResponseAPDU(b.transceive(s));
                            if (r.getSW() != 0x9000 && !force) {
                                return fail("Card returned " + String.format("%04X", r.getSW()) + ", exiting!");
                            }
                        }
                    }
                    Optional<Thread> exiter = exiter();
                    // Then run the app
                    int ret = ((SimpleSmartCardApp) sca).run(b, args);
                    exiter.map(t -> Runtime.getRuntime().removeShutdownHook(t));
                    b.close(); // Close the bibo if app was successful
                    return ret;
                } else if (sca instanceof SmartCardAppListener) {
                    exiter();
                    // Runs on separate thread
                    Thread appThread = new Thread(new CardTerminalAppRunner(factory, rdr.get().getName(), (SmartCardAppListener) sca, args));
                    appThread.start();
                    appThread.join();
                    //exiter.map(t -> Runtime.getRuntime().removeShutdownHook(t));
                    verbose("Success");
                    return 0;
                } else
                    return fail("Don't know how to handle apps of type " + Arrays.asList(sca.getClass().getInterfaces()));
            }
        } catch (CardException | Smartcardio.EstablishContextException | InterruptedException e) {
            System.err.println("Failed: " + SCard.getExceptionMessage(e));
        } catch (RuntimeException e) {
            System.err.println("App failed: " + e.getMessage());
        }
        return 66;
    }

    Optional<Thread> exiter() {
        if (verbose) {
            Thread t = new Thread(() -> System.out.printf("%n%nYou were using apdu4j. Cool!%n"));
            Runtime.getRuntime().addShutdownHook(t);
            return Optional.of(t);
        }
        return Optional.empty();
    }

    @Command(name = "apdu", description = "Send raw APDU-s (Bytes Out)")
    public int sendAPDU(@Parameters(paramLabel = "<hex>", arity = "1..*") List<byte[]> apdus) {
        // Prepend the -a ones
        List<byte[]> toCard = new ArrayList<>(Arrays.asList(this.apdus));
        // Then explicit apdu-s
        toCard.addAll(apdus);
        try (BIBO b = getBIBO(getTheTerminal(reader))) {
            for (byte[] s : toCard) {
                ResponseAPDU r = new ResponseAPDU(b.transceive(s));
                if (r.getSW() != 0x9000 && !force) {
                    return fail("Card returned " + String.format("%04X", r.getSW()) + ", exiting!");
                }
            }
        } catch (CardException e) {
            return fail("Could not connect: " + e.getMessage());
        } catch (BIBOException e) {
            return fail("Failed: " + e.getMessage());
        }
        return 0;
    }


    @Command(name = "plugins", description = "List available plugins.")
    @SuppressWarnings("deprecation")
    // Provider.getVersion()
    public int listPlugins() {
        // List TerminalFactory providers
        Provider[] providers = Security.getProviders("TerminalFactory.PC/SC");
        System.out.println("Existing TerminalFactory providers:");
        if (providers != null) {
            for (Provider p : providers) {
                System.out.printf("%s v%s (%s) from %s%n", p.getName(), p.getVersion(), p.getInfo(), Plug.pluginfile(p));
            }
        }
        return 0;
    }


    static void configureLogging() {
        // Set up slf4j simple in a way that pleases us
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");

        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss:SSS");

        if (System.getenv(ENV_APDU4J_DEBUG) != null)
            System.setProperty("org.slf4j.simpleLogger.logFile", System.getenv().getOrDefault(ENV_APDU4J_DEBUG_FILE, "apdu4j.log"));
        // Default level
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", System.getenv().getOrDefault(ENV_APDU4J_DEBUG, "warn"));
    }

    public static void main(String[] args) {
        try {
            // Configure logging
            configureLogging();

            // Parse CLI
            SCTool tool = new SCTool();
            CommandLine cli = new CommandLine(tool);

            // To support "sc gp -ldv"
            cli.setUnmatchedOptionsArePositionalParams(true);
            //cli.setStopAtUnmatched(true);
            cli.setStopAtPositional(true);
            cli.registerConverter(byte[].class, HexUtils::stringToBin);
            try {
                cli.parseArgs(args);
            } catch (ParameterException ex) { // command line arguments could not be parsed
                System.err.println(ex.getMessage());
                ex.getCommandLine().usage(System.err);
                System.exit(1);
            }

            // Run program
            cli.execute(args);
        } catch (RuntimeException e) {
            System.exit(fail("Error: " + e.getMessage()));
        }
    }


    @Override
    public Integer call() {
        // Old style shorthands
        if (list)
            return listReaders(verbose);
        if (apdus.length > 0) {
            return sendAPDU(Collections.emptyList()); // XXX: to support mixing -a and apdu subcommand
        }
        // Default is to run apps
        if (params.length > 0) {
            // app can't start with "-", so it is an unknown/unhandled parameter
            if (params[0].startsWith("-")) {
                System.err.println("Invalid parameters: " + String.join(" ", params));
                return 1;
            }

            if (apps == null)
                apps = resolveApps();

            if (apps.containsKey(params[0])) {
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
        if (lowlevel.useSUN != null && !lowlevel.useSUN.isBlank()) {
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
                if (TerminalManager.isEnabled("apdu4j.fixpath", true))
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
                System.out.println("# Using " + tf.getProvider());
            }
            return tf;
        } catch (Smartcardio.EstablishContextException e) {
            String msg = SCard.getExceptionMessage(e);
            fail("No readers: " + msg);
            return null; // FIXME sugar.
        }
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
        return protocol;
    }

    // Return a BIBO or fail
    private BIBO getBIBO(Optional<CardTerminal> rdr) throws CardException {
        if (rdr.isEmpty()) {
            exit("Specify valid reader to use with -r");
        } else {
            logger.info("Using " + rdr.get());
        }
        CardTerminal reader = rdr.get();
        if (!noWait && !reader.isCardPresent()) {
            verbose("Waiting for card ...");
            try {
                CardTerminalAppRunner.waitForCard(reader);
            } catch (InterruptedException e) {
                verbose("Interrupted!");
            }
        }
        Card c = reader.connect(getProtocol());
        final AsynchronousBIBO bibo;
        if (bareBibo) {
            bibo = CardBIBO.wrap(c);
        } else {
            bibo = GetResponseWrapper.wrap(CardBIBO.wrap(c));
        }
        // XXX: this is ugly, sync->async->sync
        return new BlockingBIBO(bibo);
    }

    // Return a terminal TODO: handle plugins
    private Optional<CardTerminal> getTheTerminal(String spec) {
        // Don't issue APDU-s internally
        if (bareBibo) {
            System.setProperty("sun.security.smartcardio.t0GetResponse", "false");
            System.setProperty("sun.security.smartcardio.t1GetResponse", "false");
            System.setProperty("jnasmartcardio.transparent", "true");
        }

        try {
            // Get the right factory, based on command line options
            factory = getTerminalFactory();
            terminals = factory.terminals();

            Optional<CardTerminal> result;
            if (forceReaderSelection) {
                result = FancyChooser.forTerminals(factory, System.getenv(ENV_APDU4J_READER), System.getenv(ENV_APDU4J_READER_IGNORE)).call();
            } else {
                List<PCSCReader> readers = TerminalManager.listPCSC(terminals.list(), null, false);
                TerminalManager.dwimify(readers, spec, System.getenv(ENV_APDU4J_READER_IGNORE));
                if (spec != null) {
                    Optional<PCSCReader> preferred = TerminalManager.toSingleton(readers, e -> e.isPreferred());
                    if (preferred.isPresent()) {
                        result = Optional.ofNullable(terminals.getTerminal(preferred.get().getName()));
                    } else {
                        System.err.println("-r/$APDU4J_READER was not found: " + spec);
                        result = Optional.empty();
                    }
                } else {
                    result = TerminalManager.getLucky(readers, terminals);
                }
                if (result.isEmpty() && spec == null) {
                    result = FancyChooser.forTerminals(factory, null, System.getenv(ENV_APDU4J_READER_IGNORE)).call();
                }
            }
            // Apply logging, if requested
            return result.map(t -> debug ? LoggingCardTerminal.getInstance(t) : t);
        } catch (Exception e) {
            System.out.println("Failed : " + e.getMessage());
            return Optional.empty();
        }
    }

    public String[] getVersion() {
        String secondLine = String.format("# Running on %s %s %s", System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"))
                + String.format(", Java %s by %s", System.getProperty("java.version"), System.getProperty("java.vendor"));

        ArrayList<String> v = new ArrayList<>();
        v.add(TerminalManager.getVersion());
        v.add(secondLine);
        List<String> env = System.getenv().entrySet().stream().filter(e -> e.getKey().startsWith("APDU4J_")).map(e -> String.format("%s=\"%s\"", e.getKey(), e.getValue())).collect(Collectors.toList());
        if (env.size() > 0)
            v.add("# " + String.join(" ", env));
        return v.toArray(new String[0]);
    }

    private static int fail(String message) {
        System.err.println(message);
        return 1;
    }

    private static void exit(String message) {
        System.exit(fail(message));
    }
}