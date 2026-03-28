module apdu4j.pcsc.sim {
    requires transitive apdu4j.core;
    requires transitive java.smartcardio;
    requires org.slf4j;

    exports apdu4j.pcsc.sim;
}
