/**
 * Copyright (c) 2021-present Martin Paljak
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p>
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

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

// This is a data-only class that combines information from CardTerminal and Card in javax.smartcardio
// TerminalManager handles the low-level PC/SC weirdnesses and returns this as reader listing in dwimList
public final class PCSCReader {
    String name;
    String aliasedName;
    byte[] atr;
    boolean present;
    boolean exclusive;
    boolean ignore;
    boolean preferred;
    String vmd;


    PCSCReader(String name, byte[] atr, boolean present, boolean exclusive, String vmd) {
        this.name = name;
        this.atr = atr;
        this.present = present;
        this.exclusive = exclusive;
        this.vmd = vmd;
    }

    public String getName() {
        return name;
    }

    public Optional<byte[]> getATR() {
        return Optional.ofNullable(atr);
    }

    public boolean isExclusive() {
        return exclusive;
    }

    public boolean isPresent() {
        return present;
    }

    public Optional<String> getVMD() {
        return Optional.ofNullable(vmd);
    }

    public boolean isIgnore() {
        return ignore;
    }

    public void setIgnore(boolean v) {
        ignore = v;
    }

    public boolean isPreferred() {
        return preferred;
    }

    public void setPreferred(boolean v) {
        preferred = v;
    }

    public String getAliasedName() {
        return aliasedName == null ? name : aliasedName;
    }

    public void setAliasedName(String v) {
        aliasedName = v;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PCSCReader that = (PCSCReader) o;
        return this.name.equals(that.name)
                && this.present == that.present
                && Arrays.equals(this.atr, that.atr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, present, Arrays.hashCode(atr));
    }

    @Override
    public String toString() {
        return "PCSCReader{" + name + "," + present + getATR().map(a -> "," + HexBytes.b(a).s()).orElse("") + "}";
    }

    // Utility function to print the terminal list in a predictable way
    public static char presenceMarker(PCSCReader r) {
        final char presentMarker;

        if (r.present && r.ignore)
            presentMarker = 'I';
        else if (r.present && r.preferred)
            presentMarker = 'P';
        else if (r.present && r.exclusive)
            presentMarker = 'X';
        else if (r.present)
            presentMarker = '*';
        else if (r.ignore)
            presentMarker = 'i';
        else if (r.preferred)
            presentMarker = 'p';
        else
            presentMarker = ' ';
        return presentMarker;
    }
}