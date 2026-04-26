// SPDX-FileCopyrightText: 2021 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import java.io.Serial;

// Not unlike CardException - happens between "here" and "secure element", for whatever reasons
public class BIBOException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 6710240956038548175L;

    public BIBOException(String message) {
        super(message);
    }

    public BIBOException(String message, Throwable e) {
        super(message, e);
    }
}
