# Agent Instructions

## Build Verification

Before concluding any task that modifies code, run a full build:

```bash
./mvnw -B clean verify
```

This runs all tests across all modules and generates the JaCoCo aggregate coverage report.

## Coverage

Always check the **aggregate** report, never per-module reports in isolation:

```
report/target/site/jacoco-aggregate/jacoco.csv
```

Parse it to get per-module and per-class coverage. The aggregate reflects cross-module test coverage (e.g., pcsc
Cucumber tests exercising core classes).

A per-module report like `core/target/site/jacoco/jacoco.csv` is misleading -it only shows coverage from that module's
own tests.

## Code Style

After modifying code, run the OpenRewrite check profile to detect style drift:

```bash
./mvnw -B verify -Pcheck
```

If it fails (dry-run finds changes), apply fixes automatically with:

```bash
./mvnw -B process-sources -Pfixup
```

Then re-run `./mvnw clean verify` to confirm the fixup didn't break anything.
