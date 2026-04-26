// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import java.io.Serial;

// Unrecoverable failure during recipe execution
public class KitchenDisaster extends RuntimeException {
    @Serial
    private static final long serialVersionUID = -2048227635135284568L;

    public KitchenDisaster(String message) {
        super(message);
    }

    public KitchenDisaster(String message, Throwable cause) {
        super(message, cause);
    }
}
