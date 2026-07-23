// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette.android;

import android.nfc.TagLostException;
import android.nfc.tech.IsoDep;
import android.util.Log;
import apdu4j.core.BIBO;
import apdu4j.core.BIBOException;
import apdu4j.prefs.Preference;
import java.io.IOException;

// A BIBO transport over an ISO-DEP tag: one transceive sends an APDU and returns the response. Closing closes
// the ISO-DEP session; the kitchen manager mints a fresh transport per session and lets the stack close it.
// Also holds the one session fact the Android NFC stack adds beyond the backend-neutral CardInfo keys.
public final class AndroidBIBO implements BIBO {

    private static final String TAG = "AndroidBIBO";

    // The disconnect disposition, fixed when the transport is built from the session's freshness contract, to
    // mirror the PC/SC transport where close() can power-cycle the card (UNPOWER) or warm-reset it. Android
    // cannot drop the RF field, so close() only ends the ISO-DEP session and can never force a power-on: a
    // required power-on is supplied by the next fresh tap, not by close(). The value is carried for parity and
    // does not change what close() does.
    public enum Disposition { RESET, LEAVE }

    // The NFC technologies the tag responds to (Tag.getTechList(), package prefixes stripped), e.g. "IsoDep, NfcA".
    // Readonly, set at tap time into the session preferences.
    public static final Preference.Parameter<String> TAG_TYPE =
            Preference.parameter("nfc.tech", String.class, true);

    private final IsoDep iso;
    private final Disposition disposition;

    AndroidBIBO(IsoDep iso, Disposition disposition) {
        this.iso = iso;
        this.disposition = disposition;
    }

    @Override
    public byte[] transceive(byte[] apdu) throws BIBOException {
        try {
            return iso.transceive(apdu);
        } catch (IOException e) {
            if (e instanceof TagLostException) {
                // Card left the field mid-APDU: the departure counterpart to onTagDiscovered.
                Log.d(TAG, "Tag left the field during transceive");
            }
            throw new BIBOException("ISO-DEP transceive failed: " + e.getMessage(), e);
        }
    }

    // Ends the ISO-DEP session. The disposition cannot change this: Android holds the RF field up, so close()
    // leaves the chip powered and can never force a power-on, whatever the freshness contract asked. A required
    // power-on comes from the next fresh tap. RESET and LEAVE therefore close the same way here.
    @Override
    public void close() {
        try {
            iso.close();
        } catch (TagLostException e) {
            // Tag already out of the field before close: the field ended the session for us.
            Log.d(TAG, "Tag already out of the field at close");
        } catch (IOException e) {
            Log.w(TAG, "ISO-DEP close failed", e);
        }
    }

    // The disposition fixed at construction. Inert at close() on Android (the field cannot be dropped); exposed
    // so a caller can read the freshness contract the transport was built with.
    public Disposition disposition() {
        return disposition;
    }
}
