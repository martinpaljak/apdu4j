/*
 * Copyright (c) 2019 Martin Paljak
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
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CardTerminals;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;

public class Fancy implements Runnable, BIBOProvider {
    MultiWindowTextGUI gui;
    Terminal terminal;
    Screen screen;
    BasicWindow mainWindow;
    CardTerminals terminals;
    CardTerminal chosenOne = null;
    ChangeListener listener;

    private BasicWindow makeMainWindow() throws IOException {
        // Create the main window
        BasicWindow mainWindow = new BasicWindow(" Choose a reader ");
        mainWindow.setHints(Arrays.asList(Window.Hint.FIT_TERMINAL_WINDOW, Window.Hint.CENTERED));
        Panel mainPanel = new Panel();
        //mainPanel.setLayoutManager(new BorderLayout());
        mainPanel.setLayoutManager(new LinearLayout(Direction.VERTICAL));
        ActionListBox mainActions = new ActionListBox();
        try {

            for (CardTerminal t : terminals.list()) {
                mainActions.addItem(t.getName(), () -> {
                    System.out.println("Chosen one: " + t);
                    chosenOne = t;
                    //mainWindow.setVisible(false);
                    try {
                        screen.close();
                        terminal.clearScreen();
                    } catch (IOException e) {
                        System.err.println("Warning: " + e.getMessage());
                    }
                });
            }

        } catch (CardException e) {
            System.err.println(e.getMessage());
            throw new IOException(e);
        }

        mainActions.setLayoutData(BorderLayout.Location.CENTER);
        mainPanel.addComponent(mainActions);

        mainPanel.addComponent(new EmptySpace(new TerminalSize(0, 1)));
        mainPanel.addComponent(new Button("Cancel and quit", () -> {
            quit();
        }).setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.End)));
        //mainPanel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.End));
        mainWindow.setComponent(mainPanel);
        return mainWindow;
    }


    synchronized void populate(CardTerminals terms) {

    }

    private void quit() {
        try {
            listener.interrupt();
            terminal.clearScreen();
            screen.close();
        } catch (IOException e) {
            System.err.println("Warning: " + e.getMessage());
        }
    }

    public Fancy() {

    }

    private Fancy create(CardTerminals terminals) throws IOException {
        this.terminals = terminals;
        terminal = new DefaultTerminalFactory().createTerminal();
        screen = new TerminalScreen(terminal);
        gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLUE));
        mainWindow = makeMainWindow();
        return this;
    }


    @Override
    public void run() {
        try {
            listener = new ChangeListener(terminals);
            listener.start();
            screen.startScreen();
            gui.addWindowAndWait(mainWindow);
            // Start the listener
        } catch (IOException e) {
            System.err.println("Not running");
        }
    }


    Optional<CardTerminal> getTerminal() {
        return Optional.ofNullable(chosenOne);
    }

    @Override
    public BIBO getBIBO() {
        // TODO: fancy stuff and DWIM
        try {
            Fancy chooser = create(TerminalManager.getTerminalFactory().terminals());
            chooser.run();
            Optional<CardTerminal> chosen = chooser.getTerminal();
            chooser.quit();
            if (chosen.isPresent()) {
                System.out.println("present: " + chooser.getTerminal().get().getName());
                return CardBIBO.wrap(chosen.get().connect("*"));
            } else {
                System.out.println("not present");
                throw new IllegalStateException("No reader chosen");
            }
        } catch (CardException | IOException | NoSuchAlgorithmException e) {
            System.err.println("Failed: " + e.getMessage());
            throw new IllegalStateException("No reader chosen");
        }
    }

    class ChangeListener extends Thread {
        final CardTerminals terms;

        ChangeListener(CardTerminals terminals) {
            terms = terminals;
        }

        @Override
        public void run() {
            try {
                System.out.println("Running");
                while (!interrupted()) {
                    boolean changed = terminals.waitForChange(3000);
                    System.out.println("Changed: " + changed);
                    if (changed) {
                        System.out.println("New size: " + terms.list().size());
                    }
                }
            } catch (CardException e) {
                System.err.println("Failed: " + e.getMessage());
            }
        }
    }
}
