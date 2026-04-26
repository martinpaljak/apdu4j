// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import apdu4j.prefs.Preferences;

import java.util.function.Function;

/**
 * BIBO Stack Application - a {@link BIBO} with a typed {@link Preferences} sidecar.
 *
 * <p>Inspired by WSGI's environ dict. Each middleware layer can read and contribute typed preferences
 * as it wraps the underlying transport, building a composable protocol stack with
 * accumulated metadata visible to callers.
 *
 * <p>BIBOSA implements {@link BIBO} so it substitutes transparently anywhere a plain
 * BIBO is expected. Two composition patterns are supported:
 * <ul>
 *   <li>{@link #then(Function)} - simple BIBO wrappers ({@code Function<BIBO, BIBO>}),
 *       preferences pass through unchanged</li>
 *   <li>{@link #then(BIBOMiddleware)} - full middleware that can both wrap the transport
 *       and add typed preferences to the stack</li>
 * </ul>
 *
 * <p>Example: building a secure channel stack with metadata propagation:
 * <pre>{@code
 * var stack = new BIBOSA(transport)
 *     .then(GetResponseWrapper::wrap)       // simple wrapper
 *     .then(secureChannelMiddleware);        // adds blockSize preference
 * int blockSize = stack.preferences().get(BLOCK_SIZE);
 * }</pre>
 *
 * @see BIBO
 * @see BIBOMiddleware
 * @see Preferences
 */
public final class BIBOSA implements BIBO {
    private final BIBO bibo;
    private final Preferences preferences;

    /**
     * Wraps a BIBO with an empty preferences sidecar.
     *
     * @param bibo the underlying transport
     */
    public BIBOSA(BIBO bibo) {
        this(bibo, new Preferences());
    }

    /**
     * Wraps a BIBO with the given preferences sidecar.
     *
     * @param bibo        the underlying transport
     * @param preferences typed metadata accumulated by middleware layers
     */
    public BIBOSA(BIBO bibo, Preferences preferences) {
        this.bibo = bibo;
        this.preferences = preferences;
    }

    @Override
    public byte[] transceive(byte[] bytes) throws BIBOException {
        return bibo.transceive(bytes);
    }

    @Override
    public void close() {
        bibo.close();
    }

    /**
     * Applies a simple BIBO wrapper, preserving preferences unchanged.
     *
     * <p>Use this for stateless wrappers like {@code GetResponseWrapper::wrap}
     * that only transform the transport without contributing metadata.
     *
     * @param wrapper function that wraps the underlying BIBO
     * @return a new BIBOSA with the wrapped transport and the same preferences
     */
    @Override
    public BIBOSA then(Function<BIBO, BIBO> wrapper) {
        return new BIBOSA(wrapper.apply(bibo), preferences);
    }

    /**
     * Applies a full middleware layer that can wrap the transport and add preferences.
     *
     * <p>Unlike {@link #then(Function)}, the middleware receives the entire BIBOSA
     * (transport + accumulated preferences) and returns a new stack with potentially
     * updated transport and enriched preferences.
     *
     * @param middleware the middleware to apply
     * @return the BIBOSA returned by the middleware
     */
    public BIBOSA then(BIBOMiddleware middleware) {
        return middleware.wrap(this);
    }

    /**
     * Applies multiple simple BIBO wrappers in left-to-right reading order.
     *
     * <p>The leftmost wrapper becomes the outermost layer (closest to the
     * application); the rightmost is innermost (closest to the wire). This
     * matches how an outbound APDU traverses the stack: it enters the leftmost
     * wrapper first and leaves the rightmost last.
     *
     * <p>Example - install GET RESPONSE chaining over a T=0 Le stripper:
     * <pre>{@code
     * stack.compose(GetResponseWrapper::wrap, T0Stripper::wrap);
     * }</pre>
     * is equivalent to {@code stack.then(T0Stripper::wrap).then(GetResponseWrapper::wrap)}
     * but reads in the same order the APDU bytes traverse the stack.
     *
     * <p>Preferences pass through unchanged. For middlewares that contribute
     * preferences, use {@link #then(BIBOMiddleware)}.
     *
     * @param wrappers wrappers to apply, leftmost = outermost
     * @return a new BIBOSA with the wrappers installed and preferences preserved
     */
    @SafeVarargs
    public final BIBOSA compose(Function<BIBO, BIBO>... wrappers) {
        BIBO result = bibo;
        // Apply rightmost first so the leftmost ends up outermost.
        for (int i = wrappers.length - 1; i >= 0; i--) {
            result = wrappers[i].apply(result);
        }
        return new BIBOSA(result, preferences);
    }

    /**
     * Adapts the stack using a preference-driven middleware factory. The factory
     * receives the accumulated preferences and returns a middleware to apply.
     *
     * <p>This enables runtime-adaptive stacks where earlier layers set
     * preferences that influence which middleware gets applied later:
     * <pre>{@code
     * stack.then(initUpdateMiddleware)                            // sets SCP_VERSION
     *      .adapt(prefs -> createWrapper(prefs.get(SCP_VERSION))); // picks SCP02 vs SCP03
     * }</pre>
     *
     * <p>Named {@code adapt} rather than {@code then} to avoid erasure clash
     * with {@link #then(Function)}.
     *
     * @param factory function that reads preferences and returns a middleware
     * @return the BIBOSA produced by the selected middleware
     */
    public BIBOSA adapt(Function<Preferences, BIBOMiddleware> factory) {
        return factory.apply(preferences).wrap(this);
    }

    /**
     * Returns the underlying BIBO transport.
     *
     * @return the wrapped BIBO
     */
    public BIBO bibo() {
        return bibo;
    }

    /**
     * Returns the accumulated preferences. Preferences are immutable,
     * so this returns the instance directly without defensive copying.
     *
     * @return the preferences sidecar
     */
    public Preferences preferences() {
        return preferences;
    }
}
