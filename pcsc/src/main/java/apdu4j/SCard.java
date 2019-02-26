package apdu4j;

public final class SCard {
    public static final String SCARD_E_SHARING_VIOLATION = "SCARD_E_SHARING_VIOLATION";
    public static final String SCARD_E_NO_READERS_AVAILABLE = "SCARD_E_NO_READERS_AVAILABLE";

    public static int CARD_CTL_CODE(int c) {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        if (os.indexOf("windows") != -1) {
            return 0x31 << 16 | c << 2;
        } else {
            return 0x42000000 + c;
        }
    }
}
