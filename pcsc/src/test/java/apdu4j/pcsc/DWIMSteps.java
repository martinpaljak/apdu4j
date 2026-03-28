/*
 * Copyright (c) 2026-present Martin Paljak <martin@martinpaljak.net>
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
