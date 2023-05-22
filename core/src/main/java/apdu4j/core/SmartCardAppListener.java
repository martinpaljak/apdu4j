/*
 * Copyright (c) 2020-present Martin Paljak
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package apdu4j.core;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

public interface SmartCardAppListener extends SmartCardApp {

    /**
     * Complete the returned future to start application (emit onCardPresent)
     *
     * @param argv similar to argv in main()
     *
     * @return the future, which completion will start the app.
     */
    CompletableFuture<AppParameters> onStart(String[] argv);

    /**
     * Called for every chip session. This means a "freshly" started chip.
     * transport.done() may or may not power down the chip for new session
     *
     * @param transport APDU transport channel to the card
     * @param properties card properties
     */
    void onCardPresent(AsynchronousBIBO transport, CardData properties);

    /**
     * Called when error occurs or when card is removed in multi-session app.
     * onError could also be triggered.
     */
    void onCardRemoved();

    /**
     * Technical error when communicating with the reader, no further communication possible.
     * May be preceded by onCardRemoved.
     *
     * @param e the exception
     */
    void onError(Throwable e);

    // Data objects
    class AppParameters extends HashMap<String, String> {
        private static final long serialVersionUID = 5410274086433485297L;
        // Set to "true" to support multiple sessions in one app
        public static final String MULTISESSION_BOOLEAN = "multisession";
        // Set to "true" to require a "clean touch" when app starts
        public static final String TOUCH_REQUIRED_BOOLEAN = "touch_required";
        // Set to the wanted protocol (T=0, T=1, T=*)
        public static final String PROTOCOL_STRING = "protocol";
    }

    class CardData extends HashMap<String, Object> {
        private static final long serialVersionUID = 2127675255938833899L;
        public static final String PROTOCOL_STRING = "protocol";
        public static final String ATR_BYTES = "atr";
        public static final String ATS_BYTES = "ats";
        public static final String NDEF_BYTES = "ndef";
    }
}
