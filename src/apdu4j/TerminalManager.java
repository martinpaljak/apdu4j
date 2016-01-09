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

import java.io.File;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CardTerminals;
import javax.smartcardio.CardTerminals.State;
import javax.smartcardio.TerminalFactory;

/**
 * Facilitates working with javax.smartcardio
 *
 * @author Martin Paljak
 *
 */
public class TerminalManager {
	protected static final String lib_prop = "sun.security.smartcardio.library";
	private static final String debian64_path = "/usr/lib/x86_64-linux-gnu/libpcsclite.so.1";
	private static final String ubuntu_path = "/lib/libpcsclite.so.1";
	private static final String ubuntu32_path = "/lib/i386-linux-gnu/libpcsclite.so.1";
	private static final String ubuntu64_path = "/lib/x86_64-linux-gnu/libpcsclite.so.1";
	private static final String freebsd_path = "/usr/local/lib/libpcsclite.so";
	private static final String fedora64_path = "/usr/lib64/libpcsclite.so.1";
	private static final String raspbian_path = "/usr/lib/arm-linux-gnueabihf/libpcsclite.so.1";

	public static TerminalFactory getTerminalFactory() throws NoSuchAlgorithmException {
		return getTerminalFactory(true);
	}

	/**
	 * Locates PC/SC shared library on the system and automagically sets system properties so that SunPCSC
	 * could find the smart card service. Call this before acquiring your TerminalFactory.
	 */
	public static void fixPlatformPaths() {
		if (System.getProperty(lib_prop) == null) {
			// Set necessary parameters for seamless PC/SC access.
			// http://ludovicrousseau.blogspot.com.es/2013/03/oracle-javaxsmartcardio-failures.html
			if (System.getProperty("os.name").equalsIgnoreCase("Linux")) {
				// Only try loading 64b paths if JVM can use them.
				if (System.getProperty("os.arch").contains("64")) {
					if (new File(debian64_path).exists()) {
						System.setProperty(lib_prop, debian64_path);
					} else if (new File(fedora64_path).exists()) {
						System.setProperty(lib_prop, fedora64_path);
					} else if (new File(ubuntu64_path).exists()) {
						System.setProperty(lib_prop, ubuntu64_path);
					}
				} else if (new File(ubuntu_path).exists()) {
					System.setProperty(lib_prop, ubuntu_path);
				} else if (new File(ubuntu32_path).exists()) {
					System.setProperty(lib_prop, ubuntu32_path);
				} else if (new File(raspbian_path).exists()) {
					System.setProperty(lib_prop, raspbian_path);
				} else {
					// XXX: dlopen() works properly on Debian OpenJDK 7
					// System.err.println("Hint: pcsc-lite probably missing.");
				}
			} else if (System.getProperty("os.name").equalsIgnoreCase("FreeBSD")) {
				if (new File(freebsd_path).exists()) {
					System.setProperty(lib_prop, freebsd_path);
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
	 *
	 * Fixes PC/SC paths for Unix systems or bypasses SunPCSC and uses JNA based approach.
	 *
	 * @param fix true if jnasmartcardio should be used
	 *
	 * @return a {@link TerminalFactory} instance
	 * @throws NoSuchAlgorithmException if jnasmartcardio is not found
	 */
	public static TerminalFactory getTerminalFactory(boolean fix) throws NoSuchAlgorithmException {
		fixPlatformPaths();
		if (fix) {
			return TerminalFactory.getInstance("PC/SC", null, new jnasmartcardio.Smartcardio());
		} else {
			return TerminalFactory.getDefault();
		}
	}

	/**
	 * Returns a card reader that has a card in it. Asks for insertion, if the system only has a single reader.
	 *
	 * @return
	 * @throws CardException
	 */
	public static CardTerminal getTheReader() throws CardException {
		try {
			String msg = "This application expects one and only one card reader (with an inserted card)";
			TerminalFactory tf = getTerminalFactory(true);
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

	// Given an instance of some Exception from a PC/SC system,
	// return a meaningful PC/SC error name.
	public static String getExceptionMessage(Exception e) {
		String classname = e.getClass().getCanonicalName();
		if (e instanceof CardException || e instanceof NoSuchAlgorithmException) {
			// This comes from SunPCSC most probably and already contains the PC/SC error in the cause
			if (e.getCause() != null) {
				if (e.getCause().getMessage() != null) {
					if (e.getCause().getMessage().indexOf("SCARD_") != -1) {
						return e.getCause().getMessage();
					}
					if (e.getCause().getMessage().indexOf("PC/SC") != -1) {
						return e.getCause().getMessage();
					}
				}
			}
		}
		// Extract "nicer" PC/SC messages
		if (classname != null && classname.equalsIgnoreCase("jnasmartcardio.Smartcardio.EstablishContextException")) {
			if (e.getCause().getMessage().indexOf("SCARD_E_NO_SERVICE") != -1)
				return "SCARD_E_NO_SERVICE";
		}
		return null;
	}
}
