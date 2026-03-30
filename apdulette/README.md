# Apdulette

Lazy, composable APDU interaction framework for Java 21+. Describe card interactions as `Recipe` values, compose
them with monadic operators, execute with a `Chef`. No I/O happens until a chef runs the recipe.

## Recipes

A `Recipe<T>` is a pure description of a card interaction that produces a value of type `T`. Composing recipes does
no I/O - it builds a data structure. Execution happens only when a `Chef` runs it.

```java
// SELECT applet, read FCI length, use it in a follow-up READ BINARY
var recipe = Cookbook.selectFCI(aid)
        .map(fci -> fci.getData().length)
        .then(len -> Cookbook.raw(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, len)));

var response = chef.cook(recipe);
```

`Cookbook` provides building blocks for common operations: `selectFCI()`, `send()`, `raw()`, `data()`, `uid()`.
It also provides taster functions (`expect()`, `data()`, `check()`, `expectAll()`) for evaluating card responses.

### Composition

`then(f)` chains a dependent operation on the result (flatMap). `map(f)` transforms the result without I/O.
`and(next)` sequences two recipes, discarding the first result:

```java
var recipe = Cookbook.selectFCI(aid)
        .and(Cookbook.uid())              // SELECT, then read UID
        .map(HexUtils::bin2hex);         // format as hex string
```

Recipes read typed `Preferences` at prepare-time, so the same recipe adapts to different card configurations
without rewriting. `Cookbook.simple()` defers APDU construction to `prepare()`:

```java
var le = Preference.of("le", Integer.class, 256, false);
var recipe = Cookbook.simple(
        prefs -> new CommandAPDU(0x00, 0xB0, 0x00, 0x00, prefs.get(le)),
        Cookbook.expect_9000());
```

### Error handling

`orElse()` falls back on SW mismatch, `recover()` inspects the error (including the full `ResponseAPDU`),
`optional()` absorbs card errors as `Optional.empty()`:

```java
// Try primary AID, fall back to secondary
var recipe = Cookbook.selectFCI(primaryAid)
        .orElse(Cookbook.selectFCI(secondaryAid));

// Try multiple alternatives, first success wins
var recipe = Cookbook.firstOf(List.of(
        Cookbook.selectFCI(aid1),
        Cookbook.selectFCI(aid2),
        Cookbook.selectFCI(aid3)));

// Probe without aborting the chain
var recipe = Cookbook.uid().optional()
        .then(opt -> opt.isPresent()
            ? Recipe.premade("UID: " + HexUtils.bin2hex(opt.get()))
            : Recipe.premade("no UID support"));
```

Errors are scoped to the current step - downstream `then()` continuations are not affected by upstream
`orElse`/`recover` handlers.

### Batch commands

Send multiple commands in a single step and evaluate all responses together:

```java
var cmds = List.of(
        new CommandAPDU(0x80, 0xE8, 0x00, 0x00, chunk1),
        new CommandAPDU(0x80, 0xE8, 0x80, 0x01, chunk2));
var recipe = Cookbook.send(cmds, 0x9000);  // checks all responses
```

## Execution

`SousChef` is the real-time executor. It transmits APDUs over a `BIBO` transport using a trampoline loop -
each `prepare()` call produces commands to send, responses come back, the taster decides what happens next
(`Ready`, `NextStep`, or `Error`):

```java
var chef = new SousChef(bibo);
var result = chef.cook(recipe);

// Or with preferences and accumulated metadata:
var dish = chef.serve(recipe, prefs);
var sessionId = dish.preferences().valueOf(SESSION_ID).orElseThrow();
```

`MiseEnPlaceChef` is the pre-computation executor. It runs recipes using declared expected responses instead
of real card I/O - useful for pre-validating recipe structure or generating APDU sequences without hardware.

## Types

`Recipe<T>` prepares into one of three `PreparationStep` variants:

- `Premade<T>` - pure value, no I/O needed
- `Ingredients<T>` - commands to send + a taster function that evaluates responses
- `Failed<T>` - known failure at prepare-time (from `Recipe.error()`)

The taster returns a `Verdict`:

- `Ready<T>` - done, here's the result
- `NextStep<T>` - continue with a new recipe (optionally enriching preferences)
- `Error<T>` - card error, carries the `ResponseAPDU` for diagnostics

Unhandled errors throw `KitchenDisaster` (extends `RuntimeException`).

## Module

Requires Java 21. Depends on `apdu4j-core` and `apdu4j-prefs`:

```xml
<dependency>
    <groupId>com.github.martinpaljak</groupId>
    <artifactId>apdu4j-apdulette</artifactId>
    <version>LATEST</version>
</dependency>
```
