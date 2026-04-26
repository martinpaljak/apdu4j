// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.*;

@SuppressWarnings("unused")
public class DWIMSteps {
    private List<PCSCReader> readers;
    private final Map<String, String> env = new HashMap<>();
    private final List<String> messages = new ArrayList<>();
    private String hint;
    private List<String> ignoreFragments = List.of();

    @Given("readers:")
    public void readers_table(DataTable table) {
        readers = table.asMaps().stream()
                .map(row -> new PCSCReader(row.get("name"), null,
                        !"no".equals(row.get("present")),
                        "yes".equals(row.get("exclusive")),
                        null, false, false))
                .toList();
    }

    @Given("no readers")
    public void no_readers() {
        readers = List.of();
    }

    @Given("environment {string} is {string}")
    public void environment_is(String var, String value) {
        env.put(var, value);
    }

    @When("I pick a reader")
    public void i_pick_a_reader() {
        // fresh instance defaults: hint=null, ignoreFragments=empty
    }

    @When("I use hint {string}")
    public void i_use_hint(String h) {
        hint = h;
    }

    @When("I ignore {string}")
    public void i_ignore(String fragment) {
        var merged = new ArrayList<>(ignoreFragments);
        merged.add(fragment);
        ignoreFragments = merged;
    }

    @When("I use env hint {string} and ignore {string}")
    public void i_select_from_environment(String readerVar, String ignoreVar) {
        hint = env.get(readerVar);
        var ignoreEnv = env.get(ignoreVar);
        ignoreFragments = Readers.parseIgnoreHints(ignoreEnv);
    }

    @Then("{string} is selected")
    public void reader_is_selected(String expected) {
        assertEquals(Readers.dwim(readers, hint, ignoreFragments, r -> true, messages::add), expected);
    }

    @Then("selection fails")
    public void selection_fails() {
        try {
            Readers.dwim(readers, hint, ignoreFragments, r -> true, messages::add);
            fail("Expected selection to fail");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Then("message contains {string}")
    public void message_contains(String fragment) {
        assertTrue(messages.stream().anyMatch(m -> m.contains(fragment)),
                "Expected message containing '%s', got: %s".formatted(fragment, messages));
    }
}
