package apdu4j;

import java.io.Console;
import java.io.IOException;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.util.ASN1Dump;

public class Shell {

	boolean running = true;
	final Console c;
	Card card;

	public Shell(Card c) {
		this.c = System.console();
		card = c;
	}

	void run () {
		while (running) {
			String s = c.readLine("apdu4j> ");
			if (s == null || s.trim().length() == 0 ) {
				System.out.println("Exiting ...");
				return;
			}
			String[] parts = s.split(" ");
			String cmd = parts[0];
			if (cmd.equalsIgnoreCase("q") || cmd.equalsIgnoreCase("exit") || cmd.equalsIgnoreCase("quit")) {
				running = false;
			} else {
				try {
					final byte [] apdu;
					try {
						apdu = HexUtils.stringToBin(s);
						System.out.println("# " + HexUtils.bin2hex(apdu));
					} catch (IllegalArgumentException e) {
						System.out.println("Invalid input: " + s);
						continue;
					}
					ResponseAPDU r = card.getBasicChannel().transmit(new CommandAPDU(apdu));
					System.out.println(HexUtils.bin2hex(r.getBytes()));

					try (ASN1InputStream ais = new ASN1InputStream(r.getData())) {
						if (ais.available() > 0 ) {
							ASN1Object d = ais.readObject();
							String txt = ASN1Dump.dumpAsString(d, true);
							System.out.println(txt);
						}
					} catch (IOException e) {
						// ignore, not possible to dump
					}
				} catch (CardException e) {
					System.out.println(TerminalManager.getExceptionMessage(e));
				}
			}
		}
	}

}
