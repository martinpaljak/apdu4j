// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import java.io.Serial;
import java.util.List;

public class NoMatchingReaderException extends IllegalStateException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final List<String> available;

    public NoMatchingReaderException(List<String> available) {
        super("No matching reader. Available: " + available);
        this.available = List.copyOf(available);
    }

    public List<String> getAvailable() {
        return available;
    }
}
