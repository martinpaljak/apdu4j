/*
 * Copyright (c) 2014-2015 Martin Paljak
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

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CardTerminals;
import javax.smartcardio.CardTerminals.State;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

import apdu4j.remote.CmdlineRemoteTerminal;
import apdu4j.remote.HTTPTransport;
import apdu4j.remote.JSONMessagePipe;
import apdu4j.remote.RemoteTerminalServer;
import apdu4j.remote.SocketTransport;
import apdu4j.remote.TestServer;
import jnasmartcardio.Smartcardio.EstablishContextException;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;


public class SCTool {
	private static final String CMD_LIST = "list";
	private static final String CMD_APDU = "apdu";
	private static final String OPT_SHELL = "shell";
	private static final String OPT_PROVIDER = "provider";

	private static final String OPT_READER = "reader";
	private static final String OPT_ALL = "all";
	private static final String OPT_VERBOSE = "verbose";
	private static final String OPT_DEBUG = "debug";
	private static final String OPT_VERSION = "version";
	private static final String OPT_ERROR = "error";
	private static final String OPT_DUMP = "dump";
	private static final String OPT_REPLAY = "replay";

	private static final String OPT_HELP = "help";
	private static final String OPT_SUN = "sun";
	private static final String OPT_T0 = "t0";
	private static final String OPT_T1 = "t1";
	private static final String OPT_EXCLUSIVE = "exclusive";
	private static final String OPT_CONNECT = "connect";
	private static final String OPT_PINNED = "pinned";
	private static final String OPT_P12 = "p12";
	private static final String OPT_WAIT = "wait";


	private static final String OPT_NO_GET_RESPONSE = "no-get-response";
	private static final String OPT_LIB = "lib";
	private static final String OPT_WEB = "web";
	private static final String OPT_PROVIDERS = "P";
	private static final String OPT_TEST_SERVER = "testserver";


	private static boolean verbose = false;

	private static OptionSet parseOptions(String [] argv) throws IOException {
		OptionSet args = null;
		OptionParser parser = new OptionParser();
		parser.acceptsAll(Arrays.asList("l", CMD_LIST), "list readers");
		parser.acceptsAll(Arrays.asList("p", OPT_PROVIDER), "specify provider").withRequiredArg();
		parser.acceptsAll(Arrays.asList("v", OPT_VERBOSE), "be verbose");
		parser.acceptsAll(Arrays.asList("d", OPT_DEBUG), "show debug");
		parser.acceptsAll(Arrays.asList("e", OPT_ERROR), "fail if not 0x9000");
		parser.acceptsAll(Arrays.asList("h", OPT_HELP), "show help");
		parser.acceptsAll(Arrays.asList("r", OPT_READER), "use reader").withRequiredArg();
		parser.acceptsAll(Arrays.asList("a", CMD_APDU), "send APDU").withRequiredArg();
		parser.acceptsAll(Arrays.asList("w", OPT_WEB), "open ATR in web");
		parser.acceptsAll(Arrays.asList("V", OPT_VERSION), "show version information");
		parser.acceptsAll(Arrays.asList("s", OPT_SHELL), "start shell");

		parser.accepts(OPT_DUMP, "save dump to file").withRequiredArg().ofType(File.class);
		parser.accepts(OPT_REPLAY, "replay command from dump").withRequiredArg().ofType(File.class);

		parser.accepts(OPT_SUN, "load SunPCSC");
		parser.accepts(OPT_CONNECT, "connect to URL or host:port").withRequiredArg();
		parser.accepts(OPT_P12, "path:pass of client PKCS#12").withRequiredArg();
		parser.accepts(OPT_PINNED, "require certificate").withRequiredArg().ofType(File.class);

		parser.accepts(OPT_PROVIDERS, "list providers");
		parser.accepts(OPT_ALL, "process all readers");
		parser.accepts(OPT_WAIT, "wait for card insertion");
		parser.accepts(OPT_T0, "use T=0");
		parser.accepts(OPT_T1, "use T=1");
		parser.accepts(OPT_EXCLUSIVE, "use EXCLUSIVE mode (JNA only)");
		parser.accepts(OPT_TEST_SERVER, "run a test server on port 10000").withRequiredArg();
		parser.accepts(OPT_NO_GET_RESPONSE, "don't use GET RESPONSE with SunPCSC");
		parser.accepts(OPT_LIB, "use specific PC/SC lib with SunPCSC").withRequiredArg();

		// Parse arguments
		try {
			args = parser.parse(argv);
			// Try to fetch all values so that format is checked before usage
			for (String s: parser.recognizedOptions().keySet()) {args.valuesOf(s);}
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

	public static void main(String[] argv) throws Exception {
		OptionSet args = parseOptions(argv);

		if (args.has(OPT_VERBOSE)) {
			verbose = true;
			// Set up slf4j simple in a way that pleases us
			System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
			if (args.has(OPT_DEBUG)) {
				System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace");
			}
			System.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
			System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
			System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");
		} else {
			System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
		}

		if (args.has(OPT_VERSION)) {
			String version = "apdu4j " + getVersion();
			// Append host information
			version += "\nRunning on " + System.getProperty("os.name");
			version += " " + System.getProperty("os.version");
			version += " " + System.getProperty("os.arch");
			version += ", Java " + System.getProperty("java.version");
			version += " by " + System.getProperty("java.vendor");
			System.out.println(version);
		}
		if (args.has(OPT_TEST_SERVER)) {
			// TODO: have the possibility to run SocketServer as well, based on argument (tcp: vs http:)
			RemoteTerminalServer srv = new RemoteTerminalServer(TestServer.class);
			srv.start(string2socket((String) args.valueOf(OPT_TEST_SERVER)));
			System.out.println("Hit ctrl-c to quit");
			while (true) {
				Thread.sleep(5000);
				srv.gc(System.currentTimeMillis() - 5 * 60 * 1000); // 5 minutes
			}
		}

		// We always bundle JNA with the tool, so add it to the providers.
		Security.addProvider(new jnasmartcardio.Smartcardio());

		// List TerminalFactory providers
		if (args.has(OPT_PROVIDERS)) {
			Provider providers[] = Security.getProviders("TerminalFactory.PC/SC");
			if (providers != null) {
				System.out.println("Existing TerminalFactory providers:");
				for (Provider p: providers) {
					System.out.println(p.getName() + " v" + p.getVersion() + " (" + p.getInfo() + ")");
				}
			}
		}

		// Fix (SunPCSC) properties on non-windows platforms
		TerminalManager.fixPlatformPaths();

		// Only applies to SunPCSC
		if (args.has(OPT_NO_GET_RESPONSE)) {
			System.setProperty("sun.security.smartcardio.t0GetResponse", "false");
			System.setProperty("sun.security.smartcardio.t1GetResponse", "false");
		}

		// Override PC/SC library path (Only applies to SunPCSC)
		if (args.has(OPT_LIB)) {
			System.setProperty("sun.security.smartcardio.library", (String) args.valueOf(OPT_LIB));
		}

		final TerminalFactory tf;
		CardTerminals terminals = null;

		try {
			// Get a terminal factory
			if (args.has(OPT_PROVIDER)) {
				tf = TerminalManager.getTerminalFactory((String)args.valueOf(OPT_PROVIDER));
			} else if (args.has(OPT_SUN)) {
				tf = TerminalManager.getTerminalFactory(TerminalManager.SUN_CLASS);
			} else {
				tf = TerminalManager.getTerminalFactory(TerminalManager.JNA_CLASS);
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
			System.err.println("No readers: " + msg);
			System.exit(1);
		}

		// Terminals to work on
		List<CardTerminal> do_readers = new ArrayList<CardTerminal>();

		try {
			// List Terminals
			if (args.has(CMD_LIST)) {
				List<CardTerminal> terms = terminals.list();
				if (verbose) {
					System.out.println("# Found " + terms.size() + " terminal" + (terms.size() == 1 ? "" : "s"));
				}
				if (terms.size() == 0) {
					System.err.println("No readers found");
					System.exit(1);
				}
				// List trminals
				for (CardTerminal t: terms) {
					String vmd = " ";
					if (verbose) {
						try (PinPadTerminal pp = PinPadTerminal.getInstance(t)) {
							pp.probe();
							// Verify, Modify, Display
							vmd += "[";
							vmd += pp.canVerify() ? "V":" ";
							vmd += pp.canModify() ? "M":" ";
							vmd += pp.hasDisplay() ? "D":" ";
							vmd += "] ";
						} catch (CardException e) {
							vmd = " [EEE] ";
						}
					}
					String present = t.isCardPresent() ? "[*]" : "[ ]";
					System.out.println(present + vmd + t.getName());

					if (args.has(OPT_VERBOSE) && t.isCardPresent()) {
						try {
							Card c = t.connect("DIRECT");
							String atr = HexUtils.bin2hex(c.getATR().getBytes()).toUpperCase();
							c.disconnect(false);
							System.out.println("          " + atr);
							if (args.has(OPT_WEB)) {
								String url = "http://smartcard-atr.appspot.com/parse?ATR=" + atr;
								if (Desktop.isDesktopSupported()) {
									Desktop.getDesktop().browse(new URI(url + "&from=apdu4j"));
								} else {
									System.out.println("          " + url);
								}
							}
						} catch (CardException e) {
							// Probably thrown because reader is in exclusive mode
							System.out.println("          " + TerminalManager.getExceptionMessage(e));
						}
					}
				}
			}

			// Select terminals to work on
			if (args.has(OPT_READER)) {
				String reader = (String) args.valueOf(OPT_READER);
				CardTerminal t = terminals.getTerminal(reader);
				if (t == null) {
					System.err.println("Reader \"" + reader + "\" not found.");
					System.exit(1);
				}
				do_readers = Arrays.asList(t);
			} else {
				do_readers = terminals.list(State.CARD_PRESENT);
				if (do_readers.size() == 0 && !args.has(CMD_LIST)) {
					// But if there is a single reader, wait for a card insertion
					List<CardTerminal> empty = terminals.list(State.CARD_ABSENT);
					if (empty.size() == 1 && args.has(OPT_WAIT)) {
						CardTerminal rdr = empty.get(0);
						System.out.println("Please enter a card into " + rdr.getName());
						if (!empty.get(0).waitForCardPresent(30000)) {
							System.out.println("Timeout.");
						} else {
							do_readers = Arrays.asList(rdr);
						}
					} else {
						System.err.println("No reader with a card found!");
						System.exit(1);
					}
				}
			}
		} catch (CardException e) {
			// Address Windows with SunPCSC
			String em = TerminalManager.getExceptionMessage(e);
			if (em.equals("SCARD_E_NO_READERS_AVAILABLE")) {
				System.err.println("No reader with a card found!");
				System.exit(1);
			} else {
				System.out.println("Could not list readers: " + em);
			}
		}
		// Do the meaningful work.
		for (CardTerminal t: do_readers) {
			if (do_readers.size() > 1 || args.has(OPT_VERBOSE)) {
				System.out.println("# " + t.getName());
			}
			try {
				work(t, args);
			} catch (CardException e) {
				if (TerminalManager.getExceptionMessage(e) == "SCARD_E_SHARING_VIOLATION") {
					continue;
				}
				throw e;
			}
		}
	}

	private static void work(CardTerminal reader, OptionSet args) throws CardException {
		if (!reader.isCardPresent()) {
			System.out.println("No card in " + reader.getName());
			return;
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

		// This allows to override the protocol for RemoteTerminal as well.
		String protocol;
		boolean transact = true;
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
		} else if (System.getProperty("os.name").toLowerCase().contains("windows") && args.has(OPT_CONNECT)) {
			// Windows 8+ have the "5 seconds of transaction" limit. Because we want reliability
			// and don't have access to arbitrary SCard* calls via javax.smartcardio, we rely on
			// JNA interface and its EXCLUSIVE access instead and do NOT use the SCardBeginTransaction
			// capability of the JNA interface.
			// https://msdn.microsoft.com/en-us/library/windows/desktop/aa379469%28v=vs.85%29.aspx
			transact = false;
			protocol = "EXCLUSIVE;" + protocol;
		}

		if (args.has(CMD_APDU) || args.has(OPT_SHELL))  {
			Card c = null;
			try {
				c = reader.connect(protocol);

				if (args.has(CMD_APDU)) {
					for (Object s: args.valuesOf(CMD_APDU)) {
						CommandAPDU a = new CommandAPDU(HexUtils.stringToBin((String)s));
						ResponseAPDU r = c.getBasicChannel().transmit(a);
						if (args.has(OPT_ERROR) && r.getSW() != 0x9000) {
							System.out.println("Card returned " + String.format("%04X", r.getSW()) + ", exiting!");
							return;
						}
					}
				} else {
					Shell s = new Shell(c);
					s.run();
					return;
				}
			} catch (CardException e) {
				if (TerminalManager.getExceptionMessage(e) != null) {
					System.out.println("PC/SC failure: " + TerminalManager.getExceptionMessage(e));
					return;
				} else {
					throw e;
				}
			} finally {
				if (c != null) {
					c.disconnect(true);
				}
			}
		} else if (args.has(OPT_CONNECT)) {
			String remote = (String) args.valueOf(OPT_CONNECT);
			JSONMessagePipe transport = null;
			KeyManagerFactory kmf = null;
			X509Certificate pinnedcert = null;


			try {
				// Connection parameters
				if (args.has(OPT_P12)) {
					String[] pathpass = args.valueOf(OPT_P12).toString().split(":");
					if (pathpass.length != 2) {
						throw new IllegalArgumentException("Must be path:password!");
					}
					kmf = SocketTransport.get_key_manager_factory(pathpass[0], pathpass[1]);
				}
				if (args.has(OPT_PINNED)) {
					pinnedcert = certFromPEM(((File)args.valueOf(OPT_PINNED)).getPath());
				}

				// Select transport
				if (remote.startsWith("http://") || remote.startsWith("https://")) {
					transport = HTTPTransport.open(new URL(remote), pinnedcert, kmf);
				} else {
					transport = SocketTransport.connect(string2socket(remote), null);
				}

				// Connect the transport and the terminal
				CmdlineRemoteTerminal c = new CmdlineRemoteTerminal(transport, reader);
				c.forceProtocol(protocol);
				c.transact(transact);
				// Run
				c.run();
			} catch (IOException e) {
				System.err.println("Communication error: " + e.getMessage());
			} finally {
				if (transport != null)
					transport.close();
			}
		}
	}

	private static void help_and_exit(OptionParser parser, PrintStream o) throws IOException {
		System.err.println("# apdu4j command line utility\n");
		parser.printHelpOn(o);
		System.exit(1);
	}

	private static X509Certificate certFromPEM(String path) throws IOException {
		try {
			String pem =  new String(Files.readAllBytes(Paths.get(path)));
			String [] lines = pem.split("\n");
			String [] b64 = Arrays.copyOfRange(lines, 1, lines.length-1);
			byte [] bytes = Base64.getDecoder().decode(String.join("", b64));
			CertificateFactory cf;
			cf = CertificateFactory.getInstance("X.509");
			X509Certificate cert = (X509Certificate)cf.generateCertificate(new ByteArrayInputStream(bytes));
			return cert;
		}
		catch (CertificateException e) {
			throw new IOException(e);
		}
	}

	public static String getVersion() {
		String version = "unknown-development";
		try (InputStream versionfile = SCTool.class.getResourceAsStream("version.txt")) {
			if (versionfile != null) {
				BufferedReader vinfo = new BufferedReader(new InputStreamReader(versionfile));
				version = vinfo.readLine();
			}
		} catch (IOException e) {
			version = "unknown-error";
		}
		return version;
	}

	private static InetSocketAddress string2socket(String s) {
		String[] hostport = s.split(":");
		if (hostport.length != 2) {
			throw new IllegalArgumentException("Can connect to host:port pairs!");
		}
		return new InetSocketAddress(hostport[0], Integer.valueOf(hostport[1]));
	}
}
