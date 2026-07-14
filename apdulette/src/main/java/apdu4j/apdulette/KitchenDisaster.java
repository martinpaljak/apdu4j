// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.core.ResponseAPDU;
import apdu4j.prefs.Preferences;

import java.io.Serial;

// Unrecoverable failure during recipe execution
public final class KitchenDisaster extends RuntimeException {
    @Serial
    private static final long serialVersionUID = -2048227635135284568L;

    private final transient ResponseAPDU response;
    private final transient Preferences preferences;

    public KitchenDisaster(String message) {
        super(message);
        this.response = null;
        this.preferences = null;
    }

    KitchenDisaster(String message, Throwable cause) {
        super(message, cause);
        this.response = null;
        this.preferences = null;
    }

    KitchenDisaster(String message, ResponseAPDU response, Preferences preferences) {
        super(message);
        this.response = response;
        this.preferences = preferences;
    }

    // The card response this disaster is anchored to, or null when the
    // failure did not come from a card exchange.
    public ResponseAPDU response() {
        return response;
    }

    // Preferences accumulated by the recipe chain up to the failure, or null
    // when the disaster did not come from the execution loop.
    public Preferences preferences() {
        return preferences;
    }
}
