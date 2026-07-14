# Apdulette

Lazy, composable APDU interaction framework for Java 21+. Describe card interactions as `Recipe` values, compose
them with monadic operators, execute with a `Chef`. No I/O happens until a chef runs the recipe.

## Recipes

A `Recipe<T>` is a pure description of a card interaction that produces a value of type `T`. Composing recipes does
no I/O - it builds a data structure. Execution happens only when a `Chef` runs it.

```java
// SELECT applet, read FCI length, use it in a follow-up READ BINARY
var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, aid, 256))
        .map(fci -> fci.getData().length)
        .then(len -> Cookbook.send(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, len)));

var response = chef.cook(recipe);
```

`Cookbook` provides building blocks for common operations: `send()`, `data()`, `uid()`.
It also provides taster functions (`expect()`, `check()`, `any()`, `all()`) for evaluating card responses.

### Composition

`then(f)` chains a dependent operation on the result (flatMap). `map(f)` transforms the result without I/O.
`and(next)` sequences two recipes, discarding the first result:

```java
var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, aid, 256))
        .and(Cookbook.uid())              // SELECT, then read UID
        .map(HexUtils::bin2hex);         // format as hex string
```

Recipes read typed `Preferences` at prepare-time, and tasters receive them at evaluate-time, so the same
recipe adapts to different card configurations without rewriting. `Cookbook.depends()` defers APDU construction to prepare-time:

```java
var le = Preference.of("le", Integer.class, 256, false);
var recipe = Cookbook.depends(le,
        value -> Cookbook.send(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, value)));
```

### Error handling

Failures come in two tiers. Card errors (`Verdict.Error`) always carry the blamed `ResponseAPDU` and are
the only recoverable tier: `orElse()` falls back, `recover()` inspects the error (including the full
`ResponseAPDU`), `optional()` absorbs it as `Optional.empty()`. Programmer and configuration errors
(`Recipe.fail()`) throw `KitchenDisaster` when the recipe is prepared and are caught by nothing.
`Recipe.cardError(response, message)` creates a card-tier failure without transmitting anything.

```java
// Try primary AID, fall back to secondary
var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, primaryAid, 256))
        .orElse(Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, secondaryAid, 256)));

// Try multiple alternatives, first success wins
var recipe = Cookbook.firstOf(aids.stream()
        .map(aid -> Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, aid, 256)))
        .toList());

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
each `prepare()` call produces commands to send, responses come back, the taster examines them alongside the current preferences and decides what happens next
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

`Recipe<T>` prepares into one of four `PreparationStep` variants:

- `Premade<T>` - pure value, no I/O needed
- `Ingredients<T>` - commands to send + a taster function that evaluates responses
- `Seasoned<T>` - injects preferences into the execution context, then continues with a recipe
- `CardError<T>` - card-tier failure carrying the blamed `ResponseAPDU`, no I/O

The taster receives the card responses and the current `Preferences`, then returns a `Verdict`:

- `Ready<T>` - done, here's the result
- `NextStep<T>` - continue with a new recipe (optionally enriching preferences)
- `Error<T>` - card error, carries the `ResponseAPDU` for diagnostics

Unhandled card errors throw `KitchenDisaster` (extends `RuntimeException`) carrying the blamed
`ResponseAPDU` via `response()`. Prepare-time failures (`Recipe.fail()`) throw it with the original
cause attached.

## Module

Requires Java 21. Depends on `apdu4j-core` and `apdu4j-prefs`:

```xml
<dependency>
    <groupId>com.github.martinpaljak</groupId>
    <artifactId>apdu4j-apdulette</artifactId>
    <version>LATEST</version>
</dependency>
```
