/*
 * Copyright (c) 2014 Martin Paljak
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

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.List;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CardTerminals;
import javax.smartcardio.CardTerminals.State;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.TerminalFactory;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;


public class SCTool {
	private static final String CMD_LIST = "list";
	private static final String CMD_APDU = "apdu";

	private static final String OPT_PROVIDER = "provider";
	private static final String OPT_READER = "reader";
	private static final String OPT_ALL = "all";
	private static final String OPT_VERBOSE = "verbose";
	private static final String OPT_DEBUG = "debug";

	private static final String OPT_HELP = "help";
	private static final String OPT_SUN = "sun";
	private static final String OPT_JNA = "jna";
	private static final String OPT_T0 = "t0";
	private static final String OPT_T1 = "t1";

	private static final String OPT_PROVIDERS = "P";

	private static final String SUN_CLASS = "sun.security.smartcardio.SunPCSC";
	private static final String JNA_CLASS = "jnasmartcardio.Smartcardio";

	private static boolean verbose = false;
	private static boolean debug = false;

	public static void main(String[] argv) throws Exception {
		OptionSet args = null;
		OptionParser parser = new OptionParser();
		parser.acceptsAll(Arrays.asList("l", CMD_LIST), "list readers");
		parser.acceptsAll(Arrays.asList("p", OPT_PROVIDER), "specify provider").withRequiredArg();
		parser.acceptsAll(Arrays.asList("v", OPT_VERBOSE), "be verbose");
		parser.acceptsAll(Arrays.asList("d", OPT_DEBUG), "enable debug");
		parser.acceptsAll(Arrays.asList("h", OPT_HELP), "show help");
		parser.acceptsAll(Arrays.asList("r", OPT_READER), "use reader").withRequiredArg();
		parser.acceptsAll(Arrays.asList("a", CMD_APDU), "send APDU").withRequiredArg();

		parser.accepts(OPT_SUN, "load SunPCSC");
		parser.accepts(OPT_JNA, "load jnasmartcardio");
		parser.accepts(OPT_PROVIDERS, "list providers");
		parser.accepts(OPT_ALL, "process all readers");
		parser.accepts(OPT_T0, "use T=0");
		parser.accepts(OPT_T1, "use T=1");



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
		if (args.has(OPT_VERBOSE)) {
			verbose = true;
		}
		if (args.has(OPT_DEBUG)) {
			debug = true;
		}

		// List TerminalFactory providers
		if (args.has(OPT_PROVIDERS)) {
			Provider providers[] = Security.getProviders("TerminalFactory.PC/SC");
			System.out.println("Existing TerminalFactory providers:");
			for (Provider p: providers) {
				System.out.println(p.getName());
			}
		}

		// Get a terminal factory
		TerminalFactory tf = null;
		// Overload if necessary
		if (args.has(OPT_PROVIDER)) {
			String pn = (String)args.valueOf(OPT_PROVIDER);
			tf = loadFactory(pn);
		} else if (args.has(OPT_SUN)) {
			tf = loadFactory(SUN_CLASS);
		} else if (args.has(OPT_JNA)) {
			tf = loadFactory(JNA_CLASS);
		} else {
			tf = TerminalFactory.getDefault();
		}

		if (verbose) {
			System.out.println("# Using " + tf.getProvider().getClass().getCanonicalName() + " - " + tf.getProvider());
		}
		CardTerminals terminals = tf.terminals();

		// List Terminals
		if (args.has("l")) {
			List<CardTerminal> terms = terminals.list();
			if (verbose) {
				System.out.println("# Found " + terms.size() + " terminal" + (terms.size() == 1 ? "" : "s"));
			}
			for (CardTerminal t: terms) {
				System.out.println((t.isCardPresent() ? "[*] " : "[ ] ") + t.getName());
				if (args.has(OPT_VERBOSE) && t.isCardPresent()) {
					Card c = t.connect("*");
					String atr = HexUtils.encodeHexString(c.getATR().getBytes()).toUpperCase();
					c.disconnect(false);
					System.out.println("    " + atr);
					System.out.println("    http://smartcard-atr.appspot.com/parse?ATR=" + atr);

				}
			}
		}
		// Select terminals to work on
		List<CardTerminal> do_readers;
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
			if (do_readers.size() > 1 && !args.hasArgument(OPT_ALL)) {
				System.err.println("More than one reader with a card found.");
				System.err.println("Run with --"+OPT_ALL+" to work with all found cards");
				System.exit(1);
			} else if (do_readers.size() == 0) {
				System.err.println("No reader with a card found!");
				System.exit(1);
			}
		}

		for (CardTerminal t: do_readers) {
			work(t, args);
		}
	}

	private static void work(CardTerminal reader, OptionSet args) throws CardException {
		if (!reader.isCardPresent()) {
			System.out.println("No card in " + reader.getName());
			return;
		}

		if (debug) {
			reader = LoggingCardTerminal.getInstance(reader);
		}

		System.out.println("Working on " + reader.getName());
		final String protocol;
		if (args.has(OPT_T0)) {
			protocol = "T=0";
		} else if (args.has(OPT_T1)) {
			protocol = "T=1";
		} else {
			protocol = "*";
		}
		Card c = null;
		try {
			c = reader.connect(protocol);

			if (args.has(CMD_APDU)) {
				for (Object s: args.valuesOf(CMD_APDU)) {
					System.out.println("Sending APDU");
					CommandAPDU a = new CommandAPDU(HexUtils.decodeHexString((String)s));
					c.getBasicChannel().transmit(a);
				}
			}
		}
		catch (CardException e) {
			if (TerminalManager.getExceptionMessage(e) != null) {
				System.out.println("PC/SC failure: " + TerminalManager.getExceptionMessage(e));
			} else {
				throw e;
			}
		} finally {
			if (c != null) {
				c.disconnect(true);
			}
		}
	}

	private static TerminalFactory loadFactory(String pn) {
		TerminalFactory tf = null;
		try {
			Class<?> cls = Class.forName(pn);
			Provider p = (Provider) cls.getConstructor().newInstance();
			tf = TerminalFactory.getInstance("PC/SC", null, p);
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
			System.err.println("Could not load " + pn + ": " + e.getClass().getCanonicalName());
			throw new RuntimeException(e);
		}
		catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("Platform does not support PC/SC");
		}
		return tf;
	}

	private static void help_and_exit(OptionParser parser, PrintStream o) throws IOException {
		System.err.println("# apdu4j command line utility\n");
		parser.printHelpOn(o);
		System.exit(1);
	}

}
