/*
 * Copyright (c) 2021-present Martin Paljak <martin@martinpaljak.net>
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
package apdu4j.pcsc;

import apdu4j.core.HexBytes;

import java.util.Optional;

// Immutable snapshot combining CardTerminal + Card state from javax.smartcardio
public record PCSCReader(String name, HexBytes atr, boolean present, boolean exclusive, String vmd, boolean preferred,
                         boolean ignored) {

    PCSCReader(String name, byte[] atr, boolean present, boolean exclusive, String vmd) {
        this(name, atr == null ? null : HexBytes.b(atr), present, exclusive, vmd, false, false);
    }

    public PCSCReader withPreferred(boolean preferred) {
        return new PCSCReader(name, atr, present, exclusive, vmd, preferred, ignored);
    }

    public PCSCReader withIgnored(boolean ignored) {
        return new PCSCReader(name, atr, present, exclusive, vmd, preferred, ignored);
    }

    public Optional<byte[]> getATR() {
        return atr == null ? Optional.empty() : Optional.of(atr.v());
    }

    public Optional<String> getVMD() {
        return Optional.ofNullable(vmd);
    }

    @Override
    public String toString() {
        return "PCSCReader{" + name + "," + present + getATR().map(a -> "," + HexBytes.b(a).s()).orElse("") + "}";
    }

    public static char presenceMarker(PCSCReader r) {
        if (r.present() && r.ignored()) {
            return 'I';
        }
        if (r.present() && r.preferred()) {
            return 'P';
        }
        if (r.present() && r.exclusive()) {
            return 'X';
        }
        if (r.present()) {
            return '*';
        }
        if (r.ignored()) {
            return 'i';
        }
        if (r.preferred()) {
            return 'p';
        }
        return ' ';
    }
}
