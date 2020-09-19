package apdu4j.core;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of SmartCardAppListener that provides CompletableFuture-s for all events
 * <p>
 * Extend this class for simple apps
 */
public class SmartCardAppFutures implements SmartCardAppListener, AsynchronousBIBO {
    //private static final Logger logger = LoggerFactory.getLogger(SmartCardAppFutures.class);

    private volatile AsynchronousBIBO transport;

    private volatile CompletableFuture<byte[]> responseFuture;
    private AtomicReference<CompletableFuture<Map>> cardPresentFuture = new AtomicReference<>(new CompletableFuture<>());
    private AtomicReference<CompletableFuture<Void>> cardRemovedFuture = new AtomicReference<>(new CompletableFuture<>());

    public SmartCardAppFutures() {
    }

    public CompletableFuture<byte[]> transmit(byte[] apdu) {
        //logger.debug("App#transmit({})", HexUtils.encode(apdu));
        if (transport == null)
            throw new IllegalStateException("No transport available yet!");

        if (responseFuture != null && !responseFuture.isDone())
            throw new IllegalStateException("Last transmit not completed yet!");

        responseFuture = transport.transmit(apdu);
        return responseFuture;
    }

    public CompletableFuture<Map> getCardPresentFuture() {
        return cardPresentFuture.get();
    }

    public CompletableFuture<Map> waitForCard(TimeUnit unit, long duration) {
        return cardPresentFuture.get().orTimeout(duration, unit);
    }

    public void close() {
        transport.close();
    }


    @Override
    public void onStart(CompletableFuture<AppParameters> properties) {
        //logger.debug("onStart()");
        // We start right away, normally
        properties.complete(new AppParameters());
    }

    @Override
    public void onCardPresent(AsynchronousBIBO transport, CardData props) {
        //logger.debug("onCardPresent() {}", props);
        this.transport = transport;
        cardPresentFuture.get().complete(props);
    }

    @Override
    public void onCardRemoved() {
        //logger.debug("onCardRemoved()");

        // Complete the transmit future exceptionally first.
        if (responseFuture != null)
            responseFuture.completeExceptionally(new TagRemovedException("onCardRemoved"));

        // We are ready for next card
        CompletableFuture<Map> newCardPresentFuture = new CompletableFuture<>();
        CompletableFuture<Void> newCardRemovedFuture = new CompletableFuture<>();

        CompletableFuture<Void> crf = cardRemovedFuture.get();
        CompletableFuture<Map> cpf = cardPresentFuture.get();
        if (cardRemovedFuture.compareAndSet(crf, newCardRemovedFuture) &&
                cardPresentFuture.compareAndSet(cpf, newCardPresentFuture)) {
            crf.complete(null);
        } else {
            onError(new IllegalStateException("futures messed up on card removal"));
        }
    }

    @Override
    public void onError(Throwable e) {
        //logger.debug("onError({})", e.getClass().getCanonicalName());
        cardPresentFuture.get().completeExceptionally(e);
        cardRemovedFuture.get().completeExceptionally(e);

        if (responseFuture != null)
            responseFuture.completeExceptionally(e);
    }

}
