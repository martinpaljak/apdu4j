// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Handle for a continuous onCard registration: close() stops dispatch, await() blocks until closed.
public final class CardWatch implements AutoCloseable {
    private final TerminalManager mgr;
    private final CountDownLatch closed = new CountDownLatch(1);

    CardWatch(TerminalManager mgr) {
        this.mgr = mgr;
    }

    // Block until closed: explicit close(), manager close, or monitor failure.
    public void await() throws InterruptedException {
        closed.await();
    }

    // True if the watch closed within timeout.
    public boolean await(Duration timeout) throws InterruptedException {
        return closed.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    // Stop new dispatches; a callback already running completes. Idempotent.
    @Override
    public void close() {
        mgr.unregisterOnCard(this);
        closed.countDown();
    }

    // Manager-side release: unblock awaiters without re-entering unregister.
    void release() {
        closed.countDown();
    }
}
