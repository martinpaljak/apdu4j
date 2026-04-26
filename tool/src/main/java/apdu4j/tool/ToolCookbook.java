// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.tool;

import apdu4j.apdulette.Cookbook;
import apdu4j.apdulette.Recipe;
import apdu4j.core.CommandAPDU;
import apdu4j.core.ResponseAPDU;

/**
 * Tool-specific recipes built on top of {@link Cookbook}.
 */
final class ToolCookbook {
    private ToolCookbook() {
    }

    /**
     * SELECT with empty AID (selects the ISD/OPEN on GlobalPlatform cards).
     */
    static Recipe<ResponseAPDU> selectOpen() {
        return Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, 256));
    }

    /**
     * GET DATA for GlobalPlatform CPLC ({@code 80 CA 9F 7F}).
     */
    static Recipe<byte[]> cplc() {
        return Cookbook.data(new CommandAPDU(0x80, 0xCA, 0x9F, 0x7F, 256));
    }
}
