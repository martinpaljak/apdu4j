// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

// Essentially simple tagged byte array. A message with nothing to carry carries no bytes.
public record RemoteMessage(Type type, byte[] payload) {
    public enum Type {
        POWERUP, // opens a session, carrying the ATR back
        POWERDOWN,
        APDU,
        ERROR, // sent as a reply only
        VENDOR // adapter-specific, contents known only to the adapter
    }

    public RemoteMessage {
        payload = payload == null ? new byte[0] : payload.clone();
    }

    public RemoteMessage(Type type) {
        this(type, new byte[0]);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
