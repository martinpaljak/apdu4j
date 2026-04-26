// SPDX-FileCopyrightText: 2021 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import java.util.List;

public interface PCSCMonitor {
    void readerListChanged(List<PCSCReader> states);

    void readerListErrored(Throwable t);
}
