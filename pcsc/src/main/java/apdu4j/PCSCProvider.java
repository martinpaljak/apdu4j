/*
 * Copyright (c) 2019-2020 Martin Paljak
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
package apdu4j;

import apdu4j.i.BIBOProvider;
import apdu4j.p.CardTerminalApp;
import apdu4j.p.CardTerminalProvider;
import com.google.auto.service.AutoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.util.Optional;

@AutoService({BIBOProvider.class, CardTerminalProvider.class})
public class PCSCProvider implements BIBOProvider, CardTerminalProvider {
    private static final Logger logger = LoggerFactory.getLogger(PCSCProvider.class);

    @Override
    public Optional<CardTerminal> getTerminal(String spec) {
        try {
            return TerminalManager.getInstance(TerminalManager.getTerminalFactory().terminals()).dwim(spec, null, null);
        } catch (CardException e) {
            logger.error("Could not get a terminal for {}: {}", spec, e.getMessage(), e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<BIBO> getBIBO(String spec) {
        try {
            String protocol = System.getProperty(CardTerminalApp.PROTOCOL_PROPERTY, "*");
            Optional<CardTerminal> terminal = TerminalManager.getInstance(TerminalManager.getTerminalFactory().terminals()).dwim(spec, null, null);
            if (terminal.isPresent()) {
                Card card = terminal.get().connect(protocol);
                return Optional.ofNullable(CardBIBO.wrap(card));
            }
        } catch (CardException e) {
            logger.error("Could not get a CardBIBO for {}: {}", spec, e.getMessage(), e);
        }
        return Optional.empty();
    }
}
