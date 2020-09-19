package apdu4j.pcsc;

import apdu4j.core.AsynchronousBIBO;
import apdu4j.core.HexUtils;
import apdu4j.core.SmartCardAppFutures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

// This is the actual app. This can be run in any runtime.
public class SampleApp extends SmartCardAppFutures {
    private static final Logger logger = LoggerFactory.getLogger(SampleApp.class);

    private final AppParameters params = new AppParameters();

    @Override
    public void onStart(CompletableFuture<AppParameters> properties) {
        logger.info("Application onStart()");
        properties.complete(params);
    }

    @Override
    public void onCardPresent(AsynchronousBIBO transport, CardData props) {
        super.onCardPresent(transport, props);
        logger.info("Received card: {}", props);

        transmit(HexUtils.hex2bin("00a4040000"))
                .thenComposeAsync(SampleApp::checkAndLog)
                .thenAcceptAsync(s -> close())
                .exceptionally((ex) -> {
                    logger.error("SampleApp failed: " + ex);
                    return null;
                });
    }

    @Override
    public void onError(Throwable e) {
        super.onError(e);
        logger.error("Application errored: {} ", e.getMessage(), e);
    }

    private static CompletableFuture<String> checkAndLog(byte[] response) {
        logger.info("We received: {}", HexUtils.bin2hex(response));
        return CompletableFuture.completedFuture("NewThing");
    }
}
