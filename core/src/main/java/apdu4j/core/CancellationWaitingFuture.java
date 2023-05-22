/*
 * Copyright (c) 2019-present Martin Paljak
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

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Like FutureTask, but get() waits for the callable to complete/return from interrupt.
 * <p>
 * CancelledException from callable is passed as-is from get(), InterruptedException turned into CancellationException
 * and other exceptions are wrapped in ExecutionException
 *
 */
public class CancellationWaitingFuture<V> implements RunnableFuture<V> {
    AtomicReference<Thread> runner = new AtomicReference<>(null);
    CountDownLatch completed = new CountDownLatch(1);
    AtomicBoolean cancelled = new AtomicBoolean(false);

    private volatile V result;
    private volatile Throwable ex;
    private Callable<V> callable;

    public CancellationWaitingFuture(Callable<V> callable) {
        this.callable = callable;
    }

    @Override
    public void run() {
        if (cancelled.get() != false || !runner.compareAndSet(null, Thread.currentThread()))
            return;
        try {
            Callable<V> c = callable;
            if (c != null) {
                try {
                    this.result = c.call();
                } catch (Throwable ex) {
                    this.ex = ex;
                }
            }
        } finally {
            completed.countDown();
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (mayInterruptIfRunning == false) {
            throw new IllegalArgumentException("mayInterruptIfRunning must be true");
        }
        if (!cancelled.compareAndSet(false, true))
            return false;

        Thread t = runner.get();
        if (t != null)
            t.interrupt();

        return true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public boolean isDone() {
        return completed.getCount() == 0;
    }

    @Override
    public V get() throws InterruptedException, ExecutionException {
        completed.await();
        if (ex != null) {
            if (ex instanceof CancellationException)
                throw (CancellationException) ex;
            if (ex instanceof InterruptedException)
                throw new CancellationException("Interrupted");
            throw new ExecutionException(ex);
        }
        return result;
    }

    @Override
    public V get(long l, TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
        if (!completed.await(l, timeUnit))
            throw new TimeoutException();
        if (ex != null) {
            if (ex instanceof CancellationException)
                throw (CancellationException) ex;
            if (ex instanceof InterruptedException)
                throw new CancellationException("Interrupted");
            throw new ExecutionException(ex);
        }
        return result;
    }
}
