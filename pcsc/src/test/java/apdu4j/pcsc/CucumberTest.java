// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;

@CucumberOptions(features = "classpath:apdu4j/pcsc", glue = "apdu4j.pcsc")
public class CucumberTest extends AbstractTestNGCucumberTests {
}
