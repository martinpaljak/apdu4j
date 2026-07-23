// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import apdu4j.prefs.Preference;
import apdu4j.prefs.Preferences;

import java.util.Set;

// The session preferences bag both backends share, so a consumer configures a run and reads back what the card
// gave without knowing which backend produced the session. Two kinds of key live here side by side. A writable
// hint is what the consumer asks for, seeded before any card is touched (PROTOCOL, FRESH, EXCLUSIVE). A readonly
// fact is what the backend observed and published back at connect time (the rest). Each hint pairs with the fact
// that answers it: PROTOCOL with NEGOTIATED_PROTOCOL, FRESH with FRESH_TAP, EXCLUSIVE with EXCLUSIVE_HELD. Hint
// and fact carry distinct keys, so publishing a fact never collides with the hint the consumer set. A
// contactless backend leaves ATR unset and fills UID/ATS instead; a contact backend fills ATR and leaves those
// unset.
public final class CardInfo {

    private CardInfo() {}

    // --- Writable hints: what the consumer asks for, seeded before a card is touched ---

    // The requested transport protocol; "*" lets the backend negotiate. Meaningful only where the interface can
    // vary (PC/SC contact); a contactless-only backend negotiates T=CL regardless. Answered by NEGOTIATED_PROTOCOL.
    public static final Preference.Default<String> PROTOCOL =
            Preference.of("session.protocol", String.class, "*", false,
                    p -> Set.of("T=0", "T=1", "T=CL", "*", "DIRECT").contains(p));

    // Whether every session requires a genuine power-on arrival: a fresh insert or tap, the card unpowered and
    // brought back up. Selects the transport's disconnect disposition at acquisition (a cold power cycle over a
    // warm reset). Answered by FRESH_TAP.
    public static final Preference.Default<Boolean> FRESH =
            Preference.of("session.fresh", Boolean.class, true, false);

    // Whether every session holds the card exclusively. Answered by EXCLUSIVE_HELD.
    public static final Preference.Default<Boolean> EXCLUSIVE =
            Preference.of("session.exclusive", Boolean.class, false, false);

    // --- Readonly facts: what the backend observed and published back at connect time ---

    // The name of the reader or terminal the session runs on.
    public static final Preference.Parameter<String> READER_NAME =
            Preference.parameter("reader.name", String.class, true);

    // Answer To Reset (ISO/IEC 7816-3), a contact card fact; unset over contactless.
    public static final Preference.Parameter<HexBytes> ATR =
            Preference.parameter("card.atr", HexBytes.class, true);

    // The negotiated transport protocol (T=0, T=1, T=CL). Answers the PROTOCOL hint.
    public static final Preference.Parameter<String> NEGOTIATED_PROTOCOL =
            Preference.parameter("card.protocol", String.class, true);

    // The contactless UID / PUPI (ISO/IEC 14443-3); unset over a contact interface.
    public static final Preference.Parameter<HexBytes> UID =
            Preference.parameter("card.uid", HexBytes.class, true);

    // Answer To Select (ISO/IEC 14443-4), the contactless counterpart of the ATR; unset over a contact interface.
    public static final Preference.Parameter<HexBytes> ATS =
            Preference.parameter("card.ats", HexBytes.class, true);

    // Whether this arrival was a genuine power-on: a fresh insert or tap, the card unpowered and brought back up.
    // Answers the FRESH hint.
    public static final Preference.Parameter<Boolean> FRESH_TAP =
            Preference.parameter("card.fresh", Boolean.class, true);

    // Whether the session holds the card exclusively. Answers the EXCLUSIVE hint.
    public static final Preference.Parameter<Boolean> EXCLUSIVE_HELD =
            Preference.parameter("card.exclusive", Boolean.class, true);

    // Keys ATR and protocol into the CardInfo vocabulary.
    public static Preferences params(byte[] atr, String protocol) {
        return params(new Preferences(), atr, protocol);
    }

    public static Preferences params(Preferences base, byte[] atr, String protocol) {
        return base
                .with(ATR, HexBytes.b(atr))
                .with(NEGOTIATED_PROTOCOL, protocol);
    }
}
