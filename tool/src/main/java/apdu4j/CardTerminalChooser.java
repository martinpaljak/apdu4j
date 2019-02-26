package apdu4j;

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
import java.util.Arrays;
import java.util.Optional;

public class CardTerminalChooser implements Runnable {
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

    CardTerminalChooser(CardTerminals terminals) throws IOException {
        this.terminals = terminals;
        terminal = new DefaultTerminalFactory().createTerminal();
        screen = new TerminalScreen(terminal);
        gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLUE));
        mainWindow = makeMainWindow();
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

    public static void main(String[] args) throws Exception {
        CardTerminalChooser chooser = new CardTerminalChooser(TerminalManager.getTerminalFactory(null).terminals());
        chooser.run();
        if (chooser.getTerminal().isPresent()) {
            System.out.println("present: " + chooser.getTerminal().get().getName());
        } else {
            System.out.println("not present");
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
