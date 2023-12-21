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

import apdu4j.pcsc.*;
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

import javax.smartcardio.CardTerminal;
import javax.smartcardio.CardTerminals;
import javax.smartcardio.TerminalFactory;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

// Implements a fancy GUI chooser for use in a terminal CLI. Uses TerminalManager
public final class FancyChooser implements Callable<Optional<CardTerminal>>, PCSCMonitor {
    private static final Logger logger = LoggerFactory.getLogger(FancyChooser.class);

    // GUI objects
    final Terminal terminal;
    final Screen screen;
    final MultiWindowTextGUI gui;

    // UI elements
    final BasicWindow mainWindow;
    final Panel mainPanel;
    final ActionListBox mainActions;
    final Button quitButton;

    // PCSC objects
    final TerminalManager manager;

    // State monitoring
    final Thread monitor;
    Map<String, PCSCReader> previousStates = new HashMap<>(); // start empty

    // The Chosen One
    volatile String nameOfChosenOne;

    // Hints
    final String preferred;
    final String ignored;

    final ReaderAliases aliases = ReaderAliases.getDefault();

    static {
        // Force the text based terminal on macOS
        if (TerminalManager.isMacOS() && System.console() != null)
            System.setProperty("java.awt.headless", "true");
    }


    // This is called from the monitor thread
    @Override
    synchronized public void readerListChanged(List<PCSCReader> readers) {
        try {
            // -1 == Selection not made; -2 == Selection is quit
            int selectedIndex = quitButton.isFocused() ? -2 : mainActions.getSelectedIndex();
            logger.info("CHANGE with selectedIndex={} and {} readers", selectedIndex, readers.size());
            mainActions.clearItems();
            Map<String, PCSCReader> statuses = readers.stream().collect(Collectors.toMap(PCSCReader::getName, e -> e));

            TerminalManager.dwimify(readers, preferred, ignored);

            // Update aliases
            List<String> names = readers.stream().map(PCSCReader::getName).collect(Collectors.toList());
            ReaderAliases alias = aliases.apply(names);

            // Get a preferred reader, taking into account aliases
            Optional<String> pref = TerminalManager.hintMatchesExactlyOne(preferred, names.stream().map(alias::extended).collect(Collectors.toList())); // wrong function
            logger.info("Preferred reader by {} is {}: ", preferred, pref);
            int current = 0; // setSelectedIndex is 0 based, of course
            for (PCSCReader r : readers) {
                int i = current++;
                final String name = r.getName();
                // Clear the ignore flag, unless card is present
                r.setIgnore(r.isPresent() && r.isIgnore());

                mainActions.addItem(String.format("[%s] %s", PCSCReader.presenceMarker(r), alias.extended(name)), () -> {
                    // This is executed on selection, on main thread
                    if (r.isExclusive()) {
                        MessageDialog warn = new MessageDialogBuilder()
                                .setTitle(" Warning! ")
                                .setText("Reader is in exclusive use by some other application")
                                .addButton(MessageDialogButton.Cancel)
                                .addButton(MessageDialogButton.Continue)
                                .build();
                        warn.setCloseWindowWithEscape(true);
                        MessageDialogButton d = warn.showDialog(gui);
                        if (d == null || d == MessageDialogButton.Cancel) {
                            return;
                        }
                        // Fall through if clause and set chosen one.
                    }
                    nameOfChosenOne = r.getName();
                    mainWindow.close();
                });

                Optional<PCSCReader> prev = Optional.ofNullable(previousStates.get(name));

                // Reset if current reader becomes exclusive (unless it is the only reader)
                if (i == selectedIndex && readers.size() > 1 && r.isExclusive() && !prev.map(PCSCReader::isExclusive).orElse(true)) {
                    logger.debug("rule 1");
                    selectedIndex = -1;
                }
                // Select if only reader becomes non-exclusive
                if (!r.isExclusive() && prev.map(PCSCReader::isExclusive).orElse(true) && readers.size() == 1) {
                    logger.debug("rule 2");
                    selectedIndex = i;
                }

                // main rule for non-events
                if (r.isPreferred() && selectedIndex < 0) {
                    logger.debug("rule pref");

                    selectedIndex = i;
                }
                // Select if new reader connected
                if (prev.isEmpty() && previousStates.size() > 0) {
                    logger.debug("rule 3");
                    selectedIndex = i;
                }

                // Select first non-exclusive, non-ignored reader on change, unless we have a preferred one
                if (selectedIndex == -1 && !r.isExclusive() && pref.isEmpty() && !r.isIgnore()) {
                    logger.debug("rule 4");
                    selectedIndex = i;
                }

                Optional<String> thisOptional = Optional.of(alias.extended(name));
                // Select preferred reader, only if other rules have not applied
                if (pref.equals(thisOptional) && selectedIndex < 0) {
                    logger.debug("rule 4.5");
                    selectedIndex = i;
                }

                // Select existing non-ignored reader if it gets a card
                if (!prev.map(PCSCReader::isPresent).orElse(false) && r.isPresent() && !r.isExclusive() && !r.isIgnore()) {
                    logger.debug("rule 5");
                    selectedIndex = i;
                }
                logger.info("{} Reader: {}, selectedIndex {}", i, r.getName(), selectedIndex);
                i++;
            }

            // Set title based on size
            if (readers.size() == 0) {
                mainWindow.setTitle(" Connect a reader ");
            } else {
                mainWindow.setTitle(" Choose a reader ");
            }

            // Update selected index and related focus
            if (selectedIndex >= 0) {
                mainActions.setSelectedIndex(selectedIndex);
                mainActions.takeFocus();
            } else if (selectedIndex == -2) {
                quitButton.takeFocus();
            }
            previousStates = statuses;
            // Refresh screen
            mainActions.invalidate();
            // XXX: make sure the panel sizes don't "wobble". Always add the empty space + quit button rows
            //mainPanel.setPreferredSize(new TerminalSize(mainActions.getPreferredSize().getColumns(), mainActions.getPreferredSize().getRows() + 2));
            // Request re-draw
            mainPanel.invalidate();
            // Update screen (this should be called from main thread?)
            gui.updateScreen();
            /*gui.getGUIThread().invokeLater(() -> {
                try {
                    gui.updateScreen();
                } catch (IOException e) {
                    logger.error("Failed to update screen: " + e.getMessage(), e);
                }
            });*/


            //logger.info("panel prefsize: {}, size: {}", mainPanel.getPreferredSize(), mainPanel.getSize());
            //logger.info("Actions prefsize: {}, size: {}", mainActions.getPreferredSize(), mainActions.getSize());
            //logger.info("panel prefsize: {} size {}", mainPanel.getPreferredSize(), mainPanel.getSize());
            //logger.info("Actions prefsize: {} size {}", mainActions.getPreferredSize(), mainActions.getSize());

            //mainActions.setPreferredSize(new TerminalSize(s.getColumns(), readers.size()));
            //screen.refresh(Screen.RefreshType.COMPLETE);

        } catch (Exception e) {
            logger.error("Exception in readerListChanged: " + e.getMessage(), e);
            System.err.println(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public void readerListErrored(Throwable t) {
        System.err.println(t.getMessage());
    }

    private FancyChooser(Terminal terminal, Screen screen, TerminalManager manager, String preferred, String ignored) {
        this.preferred = preferred;
        this.ignored = ignored;
        this.manager = manager;
        monitor = new Thread(new HandyTerminalsMonitor(manager, this));
        monitor.setName("FancyChooser monitor");
        monitor.setDaemon(true);

        this.terminal = terminal;
        this.screen = screen;
        gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLUE));
        // Create UI elements
        mainWindow = new BasicWindow(" apdu4j ");
        mainWindow.setCloseWindowWithEscape(true);
        mainWindow.setHints(Arrays.asList(Window.Hint.FIT_TERMINAL_WINDOW, Window.Hint.CENTERED));

        mainPanel = new Panel();
        mainPanel.setLayoutManager(new BorderLayout());
        mainPanel.setLayoutManager(new LinearLayout(Direction.VERTICAL));
        mainActions = new ActionListBox();
        mainActions.setLayoutData(BorderLayout.Location.CENTER);
        mainActions.takeFocus(); // Quit will be focused if no reader is selected
        mainPanel.addComponent(mainActions);
        // Empty line between quit button
        mainPanel.addComponent(new EmptySpace(new TerminalSize(0, 1)));
        quitButton = new Button("Cancel and quit", () -> mainWindow.close());
        quitButton.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.End));
        mainPanel.addComponent(quitButton);
        //mainPanel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.End));
        mainWindow.setComponent(mainPanel);
    }


    public static Callable<Optional<CardTerminal>> forTerminals(TerminalManager manager, String preferred, String ignored) throws IOException {
        // We can't do this on Windows, sorry.
        if (TerminalManager.isWindows())
            return () -> Optional.empty();
        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        Screen screen = new TerminalScreen(terminal);
        return new FancyChooser(terminal, screen, manager, preferred, ignored);
    }


    @Override
    public Optional<CardTerminal> call() {
        try {
            // Start monitor thread. This populates the selection window
            if (monitor != null)
                monitor.start();

            screen.startScreen();
            gui.addWindow(mainWindow);

            // This blocks the calling thread (should be main)
            gui.waitForWindowToClose(mainWindow);
            logger.info("waiting ended");
            terminal.clearScreen();
            screen.stopScreen();
            terminal.close();
            logger.info("cleared and stopped");

            // on OSX at least this prevents the print from last column after UI has been closed
            System.out.println();
            logger.info("getting terminal");
            if (nameOfChosenOne == null)
                return Optional.empty();
            CardTerminal t = manager.getTerminal(nameOfChosenOne);
            logger.info("terminal received");

            // Exists a chance that the reader is "lost" after it has been selected and this getTerminal call.
            // Log it here at least
            if (t == null) {
                logger.error("{} chosen but not available from CardTerminals?");
            }
            return Optional.ofNullable(t);
        } catch (IOException e) {
            logger.error("Could not run: " + e.getMessage());
        } finally {
            if (monitor != null) {
                monitor.interrupt();
            }
        }
        return Optional.empty();
    }
}
