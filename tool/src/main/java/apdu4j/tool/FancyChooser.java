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
package apdu4j.tool;

import apdu4j.pcsc.ReaderAliases;
import apdu4j.pcsc.SCard;
import apdu4j.pcsc.TerminalManager;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CardTerminals;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

public final class FancyChooser implements Callable<Optional<CardTerminal>> {
    private static final Logger logger = LoggerFactory.getLogger(FancyChooser.class);
    // Nice names
    static final String PRESENT = "*";
    static final String EMPTY = " ";
    static final String EXCLUSIVE = "X";

    final Terminal terminal;
    final Screen screen;
    final List<CardTerminal> initialList;

    // UI elements
    final MultiWindowTextGUI gui;
    final BasicWindow mainWindow;
    final Panel mainPanel;
    final ActionListBox mainActions;
    final Button quitButton;

    // State monitoring
    final Thread monitor;
    volatile HashMap<String, String> previousStates = new HashMap<>();
    volatile String status = "OK";

    // The Chosen One
    volatile CardTerminal chosenOne;
    final ReaderAliases aliases = ReaderAliases.getDefault();

    static {
        // Force the text based terminal on macOS
        if (isMacOS() && System.console() != null)
            System.setProperty("java.awt.headless", "true");
    }

    // TODO: have a simple fallback chooser with a list and "enter 1..N"
    static class EmptyChooser implements Callable<Optional<CardTerminal>> {
        @Override
        public Optional<CardTerminal> call() {
            return Optional.empty();
        }
    }

    private FancyChooser(Terminal terminal, Screen screen, CardTerminals monitorObject, List<CardTerminal> terminals) {
        if (monitorObject != null)
            monitor = new MonitorThread(monitorObject);
        else monitor = null;
        this.terminal = terminal;
        this.screen = screen;
        gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLUE));
        this.initialList = terminals;
        // Create UI elements
        mainWindow = new BasicWindow(" apdu4j ");
        mainWindow.setCloseWindowWithEscape(true);
        mainWindow.setHints(Arrays.asList(Window.Hint.FIT_TERMINAL_WINDOW, Window.Hint.CENTERED));

        mainPanel = new Panel();
        mainPanel.setLayoutManager(new BorderLayout());
        mainPanel.setLayoutManager(new LinearLayout(Direction.VERTICAL));
        mainActions = new ActionListBox();
        mainActions.setLayoutData(BorderLayout.Location.CENTER);
        mainPanel.addComponent(mainActions);
        mainPanel.addComponent(new EmptySpace(new TerminalSize(0, 1)));
        quitButton = new Button("Cancel and quit", () -> mainWindow.close());
        quitButton.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.End));
        mainPanel.addComponent(quitButton);
        //mainPanel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.End));
        mainWindow.setComponent(mainPanel);
    }


    public static Callable<Optional<CardTerminal>> forTerminals(CardTerminals terminals) throws IOException, CardException {
        if (isWindows())
            return new EmptyChooser();
        List<CardTerminal> terminalList = terminals.list();
        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        Screen screen = new TerminalScreen(terminal);
        return new FancyChooser(terminal, screen, terminals, terminalList);
    }


    public static Callable<Optional<CardTerminal>> forTerminals(List<CardTerminal> terminals) throws IOException {
        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        Screen screen = new TerminalScreen(terminal);
        return new FancyChooser(terminal, screen, null, terminals);
    }

    // Returns true if terminal is in exclusive use
    private boolean isExlusive(CardTerminal t) {
        boolean result = false;
        Card c = null;
        // Try shared mode, to detect exclusive mode via exception
        try {
            c = t.connect("*");
        } catch (CardException e) {
            String err = TerminalManager.getExceptionMessage(e);
            // Detect exclusive mode. Hopes this always succeeds
            if (err.equals(SCard.SCARD_E_SHARING_VIOLATION))
                result = true;
        } finally {
            if (c != null) {
                try {
                    c.disconnect(false);
                } catch (CardException e) {
                    // FIXME: log
                }
            }
        }
        return result;
    }

    // When selection changes (callable from another thread)
    synchronized void setSelection(List<CardTerminal> terminals) {
        try {
            // -1 == Selection not made; -2 == Selection is quit
            int previouslySelected = quitButton.isFocused() ? -2 : mainActions.getSelectedIndex();
            //setStatus("set " + previouslySelected);
            int selectedIndex = previouslySelected;
            mainActions.clearItems();
            HashMap<String, String> statuses = new HashMap<>();
            int i = 0;
            for (CardTerminal t : terminals) {
                final String name = t.getName();
                final boolean present = t.isCardPresent();
                final boolean exclusive = isExlusive(t);
                String status = EMPTY;
                if (present)
                    status = PRESENT;
                if (exclusive)
                    status = EXCLUSIVE;
                statuses.put(name, status);
                mainActions.addItem(String.format("[%s] %s", status, aliases.translate(name)), () -> {
                    if (exclusive) {
                        MessageDialog warn = new MessageDialogBuilder()
                                .setTitle(" Warning! ")
                                .setText("Reader is in exclusive use by some other application")
                                .addButton(MessageDialogButton.Cancel)
                                .addButton(MessageDialogButton.Continue)
                                .build();
                        warn.setCloseWindowWithEscape(true);
                        MessageDialogButton r = warn.showDialog(gui);
                        if (r == null || r == MessageDialogButton.Cancel) {
                            return;
                        }
                        // Continue below
                    }
                    chosenOne = t;
                    mainWindow.close();
                });


                // Reset if reader becomes exclusive (unless it is the only reader)
                if (i == selectedIndex && exclusive && !previousStates.get(name).equals(EXCLUSIVE) && terminals.size() > 1)
                    selectedIndex = -1;
                // Select if only reader becomes non-exclusive
                if (!exclusive && previousStates.getOrDefault(name, "").equals(EXCLUSIVE) && terminals.size() == 1)
                    selectedIndex = i;
                // New reader connected
                if (!previousStates.containsKey(name))
                    selectedIndex = i;
                // Select first non-exclusive reader
                if (selectedIndex == -1 && !exclusive)
                    selectedIndex = i;
                // Existing reader got a card
                if (previousStates.getOrDefault(name, "").equals(EMPTY) && present && !exclusive)
                    selectedIndex = i;
                i++;
            }

            // Set title
            if (terminals.size() == 0) {
                mainWindow.setTitle(" Connect a reader ");
            } else {
                mainWindow.setTitle(" Choose a reader ");
            }

            // Update selected index and related focus
            if (selectedIndex >= 0) {
                mainActions.setSelectedIndex(selectedIndex);
                mainActions.takeFocus();
            } else {
                quitButton.takeFocus();
            }
            previousStates = statuses;
            // Refresh screen
            mainPanel.invalidate();
            //screen.refresh(Screen.RefreshType.COMPLETE);
            gui.updateScreen();
        } catch (CardException | IOException e) {
            System.err.println(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<CardTerminal> call() {
        try {
            setSelection(initialList);

            // Start monitor thread
            if (monitor != null)
                monitor.start();

            screen.startScreen();
            gui.addWindow(mainWindow);
            gui.waitForWindowToClose(mainWindow);

            terminal.clearScreen();
            screen.stopScreen();
            terminal.close();

            // on OSX at least this prevents the print from last column
            System.out.println();
            return Optional.ofNullable(chosenOne);
        } catch (IOException e) {
            logger.error("Could not run: " + e.getMessage());
        } finally {
            if (monitor != null) {
                monitor.interrupt();
            }
        }
        return Optional.empty();
    }

    class MonitorThread extends Thread {
        final CardTerminals terms;

        MonitorThread(CardTerminals terminals) {
            terms = terminals;
            setName("MonitorThread");
            setDaemon(true);
        }

        @Override
        public void run() {
            while (!isInterrupted()) {
                boolean changed = true;
                try {
                    changed = terms.waitForChange(3000);
                } catch (CardException e) {
                    // Removing on Linux results in timeout error, adding results in true
                    if (TerminalManager.getPCSCError(e).equals(Optional.of(SCard.SCARD_E_TIMEOUT)))
                        changed = true;
                    else
                        logger.error("Failed: " + e.getMessage());
                }
                try {
                    if (changed) {
                        setSelection(terms.list());
                    }
                } catch (CardException e) {
                    logger.error("Failed: " + e.getMessage());
                }
            }
        }
    }

    static boolean isMacOS() {
        return System.getProperty("os.name").equalsIgnoreCase("mac os x");
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().startsWith("windows");
    }
}
