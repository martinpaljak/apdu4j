// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT

module apdu4j.remote {
    requires transitive apdu4j.core;
    requires org.slf4j;
    requires com.fasterxml.jackson.databind;

    exports apdu4j.remote;
}
