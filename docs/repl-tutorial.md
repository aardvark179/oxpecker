# The generation layer from jshell

A walk through every step of the pipeline by hand: generate a program, read its IL, lift it to
JavaScript, ask the analysis what it knows, build IL yourself, run the smaller strategies one at
a time, mutate, and run the result in both Rhino modes. Every `java` block in this file is
evaluated by `dev.oxpecker.ReplTutorialTest` in order, in one jshell session, so the examples
cannot drift from the code; the blocks under "Run it in Rhino" run only in the slow suite
(`./gradlew test -Pslow`), which needs a built harness. The reader's guide
`docs/implementation-generation-layer.md` explains the design; this file only shows how to drive it.

Build the orchestrator jar and start a shell on it from the repository root:

```sh
./gradlew :oxpecker-fuzz:fatJar
jshell --class-path oxpecker-fuzz/build/libs/oxpecker-all.jar -R-Doxpecker.root=$PWD
```

Nothing has to be loaded first: the generator and template registries fill themselves on first
use (`Strategies.registered()`).

```java
import dev.oxpecker.il.*;
import dev.oxpecker.il.generators.*;
import dev.oxpecker.gen.*;
import java.util.*;
```

## 1. Generate a whole program

```java
var g = Strategies.generate(3);             // same seed, same program, in any shell or test
System.out.println(g.source());             // the probed JavaScript the harness runs
System.out.println(g.plainSource());        // the same without probes and comments: a reproducer
g.template();
```

`generate` takes the generation options; the defaults are `GenOptions.DEFAULT`, and
`GenOptions.builder()` changes them:

```java
var small = Strategies.generate(3, GenOptions.builder().maxStatements(4).maxDepth(1).probeEvery(0).build());
var onlyControl = Strategies.generate(3, GenOptions.builder().families(Set.of("values", "control")).build());
var oneGenerator = Strategies.generate(3, GenOptions.builder().generators(Set.of("while_loop", "integer")).build());
var noLet = Strategies.generate(3, GenOptions.builder().toggles(Set.of("generators", "for-of")).build());
var weighted = Strategies.generate(3, GenOptions.builder().weights(Map.of("control", 3.0, "binary", 0.5)).build());
var tmpl = Strategies.generate(3, GenOptions.DEFAULT, "jit1_function");      // fix the template
if (!tmpl.template().equals("jit1_function")) throw new AssertionError();
```

A `Generated` has `program()` (a `Program`: `code()`, `options()`, `template()`), `code()` (the
instructions), `source()` and `plainSource()`. The templates and generator names, by family:

```java
Strategies.templates();                     // [jit1_function, jit2_functions, jit_tricky_function, plain]
Strategies.generators("control");           // [block_statement, compare_with_if_else, ...]
```

## 2. Print the IL

```java
System.out.println(Program.dump(g.code()));  // one instruction per line, blocks indented
g.program().describe();                       // <Program jit2_functions: 52 instructions, <signature>>
g.code().get(0);                              // one Instruction: outputs <- Operation(fields) inputs
```

## 3. Lift IL to JavaScript

```java
Lifter.lift(g.code(), LiftOptions.DEFAULT);                             // probes and generator comments
Lifter.lift(g.code(), LiftOptions.PLAIN);                               // neither
Lifter.lift(g.code(), LiftOptions.DEFAULT.withStrict(true).withProbeWidth(2));
```

Whether a value is inlined or bound to a `var`/`let`/`const` is each instruction's `lift()`
choice, drawn after generation (`Strategies.liftingChoices`).

## 4. Explain and analyse

```java
System.out.println(Analysis.explain(g.code()));      // types, storage, the Rhino model per variable
var an = Analysis.analyse(g.code());
an.rhino().activation();                               // function index -> why Rhino keeps an activation frame
an.rhino().numberVar();                                // the variables the optimizer types as numbers
Analysis.verify(g.code());                             // throws InvalidProgram on what no generator may produce
```

## 5. Build IL by hand

The builder emits instructions and answers the generators' questions (visible variables, types,
the Rhino model); it never draws. Blocks are try-with-resources handles:

```java
var b = new Builder();
int n = b.declare(b.loadInt(1));
b.update(n, b.loadInt(2), BinaryOperator.ADD);
int f;
try (var fb = b.plainFunction(new Parameters(1), Builder.fn().named(true).types(Type.NUMBER))) {
    f = fb.f();
    try (var br = b.if_(b.compare(fb.arg(0), b.loadInt(0), CompareOperator.GREATER))) {
        b.doReturn(fb.arg(0));
        try (var e = br.else_()) { b.doReturn(b.loadInt(0)); }
    }
}
b.callFunction(f, List.of(n));
var code = b.finish();
System.out.println(Lifter.js(code));
System.out.println(Analysis.explain(code));
```

## 6. The smaller strategies, one at a time

A generator alone, after a small prefix of values:

```java
var s = Strategies.runGenerator("while_loop", 1);
s.emitted();                                // just what while_loop emitted
System.out.println(s.js());
```

Any strategy runs once from a seed with `Strategies.draw`; a `Gen` is a lambda over a `Draw`,
whose primitives (`ints`, `bool`, `maybe`, `pick`, `lists`, `draw`) are every random decision a
generator makes:

```java
Strategies.draw(d -> d.lists(1, 5, e -> e.ints(0, 9)), 7);
Strategies.draw(d -> {
    var bb = new Builder();
    int v = Strategies.variable(d, bb, Type.STRING);    // a visible string, or a fresh one
    return Lifter.js(bb.finish());
}, 2);
```

What the engine records is the choice sequence; replaying it gives the same value, and that is
what the corpus stores:

```java
var drawn = Engine.fresh(Strategies.programs(GenOptions.DEFAULT), 3);
drawn.choices().size();
drawn.choices().encode();                                                     // "j1:..." as in corpus.jsonl
Engine.replay(Strategies.programs(GenOptions.DEFAULT), drawn.choices()).source().equals(drawn.value().source());
```

## 7. Mutate

```java
var m = Mutate.mutate(g.program(), 1);       // one mutation by a drawn mutator (throws Mutate.Rejected if it does not apply)
m.name();
System.out.println(m.diff());                // the IL before and after
Mutate.mutators();                           // operation, input, declaration_kind, reassign, codegen, ...
var l = Mutate.lineage(2, 4);                // a base program and up to four mutation steps
l.stepNames();
System.out.println(l.source());
```

`Mutate.mutate(program, seed, "declaration_kind", 1)` fixes the mutator; a seed whose choice is
not applicable throws `Mutate.Rejected`, so try another seed.

## 8. Run it in Rhino

The harness runs each mode in its own JVM; the ensemble compares every comparator against the
guide (interpreted, by default):

```java
import dev.oxpecker.harness.*;
var h = Ensemble.fromModes(false, true).start();
var run = h.run(g.source(), RunOptions.DEFAULT);
run.results().keySet();                              // [interpreted, compiled]
System.out.println(Compare.compareRun(run).pretty());
var v = Compare.compareRun(h.run(m.source(), RunOptions.DEFAULT));
v.kind;                                              // equal, java-types, soft or hard
h.stop();
```

## 9. Things that bite

- **The same seed is not the Python program.** Before the port, generation drew through
  Hypothesis; seeds and corpora from then give different programs here.
- **Draw through the `Draw` you are given.** A nested lambda that uses an outer `d` fails with
  jetCheck's `WrongDataStructure`: every sub-draw has its own.
- **`typeof` and `typeOf`.** `b.typeof(x)` emits the JavaScript operator; `b.typeOf(v)` asks the
  typer for a type.
- **Registry names, not JavaScript.** Builtins, keys and methods are looked up by their registry
  name: `Env.builtin("ARRAY")`, `Env.k("LENGTH")`, `Env.method("ARRAY_PUSH")`.
