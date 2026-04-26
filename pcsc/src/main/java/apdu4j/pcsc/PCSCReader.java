// SPDX-FileCopyrightText: 2021 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
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
