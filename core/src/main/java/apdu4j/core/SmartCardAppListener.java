package apdu4j.core;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

public interface SmartCardAppListener {

    /**
     * Complete the passed in future to start application (emit onCardPresent)
     *
     * @param properties
     */
    void onStart(CompletableFuture<AppParameters> properties);

    /**
     * Called for every session. This means a "freshly" started chip.
     * transport.done() may and may not power down the chip for new session
     */
    void onCardPresent(AsynchronousBIBO transport, CardData properties);

    /**
     * called when error occurs or when card removed in multi-session
     */
    void onCardRemoved();

    /**
     * Technical error when communicating with the reader, no further communication possible.
     */
    void onError(Throwable e);

    // Data objects
    class AppParameters extends HashMap<String, String> {
        public static final String MULTISESSION_BOOLEAN = "multisession";
        public static final String TOUCH_REQUIRED_BOOLEAN = "touch_required";
        public static final String PROTOCOL_STRING = "protocol";
    }

    class CardData extends HashMap<String, Object> {
    }
}
