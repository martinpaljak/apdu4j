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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of SmartCardAppListener that provides CompletableFuture-s for all events
 * <p>
 * Extend this class for simple app flow with futures
 */
public abstract class SmartCardAppFutures implements SmartCardAppListener, AsynchronousBIBO {

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
    public CompletableFuture<AppParameters> onStart(String[] argv) {
        // We start right away, normally
        return CompletableFuture.completedFuture(new AppParameters());
    }

    @Override
    public void onCardPresent(AsynchronousBIBO transport, CardData props) {
        this.transport = transport;
        cardPresentFuture.get().complete(props);
    }

    @Override
    public void onCardRemoved() {
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
        cardPresentFuture.get().completeExceptionally(e);
        cardRemovedFuture.get().completeExceptionally(e);

        if (responseFuture != null)
            responseFuture.completeExceptionally(e);
    }

}
