// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import javax.smartcardio.CardException;

// A card that honors PC/SC semantics the JDK javax.smartcardio.Card API cannot express.
// The disconnect-side counterpart to EXCLUSIVE connect: a wrapper implements this so a full
// disposition (UNPOWER) survives down to the real backend instead of narrowing to a boolean.
public interface PCSCCard {
    void disconnect(SCard.Disconnect how) throws CardException;
}
