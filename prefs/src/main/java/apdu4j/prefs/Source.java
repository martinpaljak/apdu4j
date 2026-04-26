// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.prefs;

// Well-known provenance labels for Preferences.Sourced.
// The source field remains free-form String; these are the standard values.
public interface Source {
    String ENV = "env";
    String PROP = "prop";
    String FILE = "file";
    String CLI = "cli";
    String CODE = "code";
    String DEFAULT = "default";
}
