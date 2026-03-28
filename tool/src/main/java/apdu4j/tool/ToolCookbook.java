/*
 * Copyright (c) 2026-present Martin Paljak <martin@martinpaljak.net>
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
        return Cookbook.selectFCI(new byte[0]);
    }

    /**
     * GET DATA for GlobalPlatform CPLC ({@code 80 CA 9F 7F}).
     */
    static Recipe<byte[]> cplc() {
        return Cookbook.data(new CommandAPDU(0x80, 0xCA, 0x9F, 0x7F, 256));
    }
}
