// SPDX-FileCopyrightText: 2020 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
module apdu4j.pcsc {
    requires transitive apdu4j.core;
    requires transitive apdu4j.prefs;

    requires transitive apdu4j.pcsc.sim;
    requires apdu4j.jnasmartcardio;
    requires transitive java.smartcardio;
    requires org.slf4j;
    requires org.yaml.snakeyaml;

    exports apdu4j.pcsc;
    exports apdu4j.pcsc.terminals;
}
