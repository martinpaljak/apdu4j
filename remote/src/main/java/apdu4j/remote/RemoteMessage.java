// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

// Essentially simple tagged byte array.
public class RemoteMessage {
    public enum Type {
        POWERUP,
        POWERDOWN,
        RESET,
        ATR,
        APDU,
        ERROR
    }

    byte[] payload;
    Type type;

    public RemoteMessage(Type type, byte[] payload) {
        this.payload = payload.clone();
        this.type = type;
    }

    public RemoteMessage(Type type) {
        this.type = type;
        this.payload = null;
    }

    public Type getType() {
        return type;
    }

    public byte[] getPayload() {
        return payload == null ? null : payload.clone();
    }
}
