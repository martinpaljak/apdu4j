// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import apdu4j.core.BIBO;
import apdu4j.core.BIBOException;
import apdu4j.core.BIBOSA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

// Per-reader single-threaded executor.
// All PC/SC operations for a reader are serialized on this thread.
public final class ReaderExecutor implements Executor {
    private static final Logger logger = LoggerFactory.getLogger(ReaderExecutor.class);

    private final ExecutorService executor;
    private final String readerName;
    private volatile Thread worker;

    ReaderExecutor(String readerName) {
        this.readerName = readerName;
        // Single daemon thread, SynchronousQueue: models PC/SC one-operation-at-a-time
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                r -> {
                    var t = new Thread(r, readerName);
                    t.setDaemon(true);
                    worker = t;
                    return t;
                },
                new CallerBlocksPolicy(100));
    }

    public String name() {
        return readerName;
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        var cf = new CompletableFuture<T>();
        // A task submitted from the reader thread itself runs inline. Ops for a reader are already
        // serialized on this thread, so re-entrant work keeps that order and does not wait for a free
        // slot that only frees when the caller returns.
        if (Thread.currentThread() == worker) {
            try {
                cf.complete(task.call());
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
            return cf;
        }
        try {
            executor.execute(() -> {
                try {
                    cf.complete(task.call());
                } catch (Throwable t) {
                    cf.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            cf.completeExceptionally(e);
        }
        return cf;
    }

    public CompletableFuture<Void> run(Runnable task) {
        return submit(() -> {
            task.run();
            return null;
        });
    }

    // BIBOSA overload: the bare-BIBO wrap() would drop the Preferences sidecar.
    public static BIBOSA wrap(ReaderExecutor executor, BIBOSA delegate) {
        return new BIBOSA(wrap(executor, delegate.bibo()), delegate.preferences());
    }

    // Thread-safe BIBO proxy: transceive() calls are marshaled to the executor thread.
    // No timeout - PC/SC has its own; RSA keygen or pinpad PIN entry can take minutes.
    public static BIBO wrap(ReaderExecutor executor, BIBO delegate) {
        return new BIBO() {
            @Override
            public byte[] transceive(byte[] bytes) throws BIBOException {
                try {
                    return executor.submit(() -> delegate.transceive(bytes)).get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof BIBOException b) {
                        throw b;
                    }
                    throw new BIBOException(e.getCause().getMessage(), e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BIBOException("interrupted", e);
                }
            }

            @Override
            public void close() {
                try {
                    executor.run(delegate::close).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    logger.warn("BIBO close failed: {}", e.getMessage());
                }
            }
        };
    }

    @Override
    public void execute(Runnable command) {
        executor.execute(command);
    }

    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow(); // Shutdown interrupted -force stop and propagate
            Thread.currentThread().interrupt();
        }
    }

    // Bridges the micro-window between TPE task completion and worker thread returning to queue.take().
    // SynchronousQueue.offer() fails instantly if nobody is waiting; this retries with a timed offer.
    // Same pattern as Spring Integration's CallerBlocksPolicy.
    static final class CallerBlocksPolicy implements RejectedExecutionHandler {
        private final long maxWaitMs;

        CallerBlocksPolicy(long maxWaitMs) {
            this.maxWaitMs = maxWaitMs;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("Executor shut down");
            }
            try {
                if (!executor.getQueue().offer(r, maxWaitMs, TimeUnit.MILLISECONDS)) {
                    throw new RejectedExecutionException("Executor busy");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted", e);
            }
        }
    }
}
