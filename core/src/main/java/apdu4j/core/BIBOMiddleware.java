// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

/**
 * A composable BIBO middleware layer that can wrap the transport and contribute
 * typed preferences to the stack.
 *
 * <p>Unlike {@code Function<BIBO, BIBO>} (which only sees the raw transport),
 * a middleware receives the full {@link BIBOSA} - both the BIBO transport and
 * the accumulated {@link apdu4j.prefs.Preferences} - and returns an updated stack.
 * This allows middleware to propagate metadata (block sizes, session keys, protocol
 * parameters) alongside the transport wrapping.
 *
 * <p>As a {@link FunctionalInterface}, middleware can be expressed as a lambda:
 * <pre>{@code
 * BIBOMiddleware scp = stack -> {
 *     var session = openSecureChannel(stack.bibo(), keys);
 *     return new BIBOSA(session, stack.preferences().with(BLOCK_SIZE, session.maxPayload()));
 * };
 * var secured = new BIBOSA(transport).then(scp);
 * }</pre>
 *
 * @see BIBOSA#then(BIBOMiddleware)
 */
@FunctionalInterface
public interface BIBOMiddleware {
    /**
     * Wraps the given BIBOSA stack, potentially replacing the transport
     * and enriching the preferences sidecar.
     *
     * @param stack the current BIBOSA (transport + accumulated preferences)
     * @return a new BIBOSA with updated transport and/or preferences
     */
    BIBOSA wrap(BIBOSA stack);

    /**
     * Returns a no-op middleware that passes the stack through unchanged.
     * Useful in conditional composition with preference-driven factories:
     * <pre>{@code
     * stack.then(prefs -> prefs.get(USE_SM)
     *     ? secureChannelMiddleware
     *     : BIBOMiddleware.identity());
     * }</pre>
     *
     * @return a middleware that returns its input unchanged
     */
    static BIBOMiddleware identity() {
        return stack -> stack;
    }
}
