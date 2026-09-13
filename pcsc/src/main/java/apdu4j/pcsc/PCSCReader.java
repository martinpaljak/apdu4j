// SPDX-FileCopyrightText: 2021 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import apdu4j.core.HexBytes;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

// Immutable snapshot combining CardTerminal + Card state from javax.smartcardio
public record PCSCReader(String name, HexBytes atr, HexBytes uid, Set<Flag> flags) {

    // PRESENT..PROBE_ERROR are observed from PC/SC, PREFERRED and IGNORED come from the consumer
    public enum Flag {PRESENT, MUTE, EXCLUSIVE, CONTACTLESS, VERIFY, MODIFY, DISPLAY, PROBE_ERROR, PREFERRED, IGNORED}

    public PCSCReader {
        // EnumSet.copyOf() throws on an empty set that is not an EnumSet
        var copy = EnumSet.noneOf(Flag.class);
        copy.addAll(flags);
        flags = Collections.unmodifiableSet(copy);
    }

    public boolean present() {
        return flags.contains(Flag.PRESENT);
    }

    public boolean mute() {
        return flags.contains(Flag.MUTE);
    }

    public boolean exclusive() {
        return flags.contains(Flag.EXCLUSIVE);
    }

    public boolean contactless() {
        return flags.contains(Flag.CONTACTLESS);
    }

    public boolean preferred() {
        return flags.contains(Flag.PREFERRED);
    }

    public boolean ignored() {
        return flags.contains(Flag.IGNORED);
    }

    public PCSCReader withPreferred(boolean preferred) {
        return with(Flag.PREFERRED, preferred);
    }

    public PCSCReader withIgnored(boolean ignored) {
        return with(Flag.IGNORED, ignored);
    }

    private PCSCReader with(Flag flag, boolean on) {
        var f = EnumSet.noneOf(Flag.class);
        f.addAll(flags);
        if (on) {
            f.add(flag);
        } else {
            f.remove(flag);
        }
        return new PCSCReader(name, atr, uid, f);
    }

    public Optional<byte[]> getATR() {
        return atr == null ? Optional.empty() : Optional.of(atr.v());
    }

    @Override
    public String toString() {
        return "PCSCReader{" + name + "," + flags + getATR().map(a -> "," + HexBytes.b(a).s()).orElse("") + (uid == null ? "" : "," + uid.s()) + "}";
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
