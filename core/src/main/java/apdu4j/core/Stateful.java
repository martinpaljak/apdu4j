// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

// A value paired with evolved state, returned by wrap/unwrap functions
public record Stateful<T, S>(T value, S state) {
}
