// Rhino 1.9.2: a labelled `for` (or `for-in`) whose head holds a function containing a loop,
// with a `continue` naming the label in the body, is miscompiled in both modes. Compiled code
// does not compile: Codegen throws RuntimeException "unlocated label" for the whole script, so
// it crashes before running a line. The interpreter runs, but `continue L` jumps past the
// loop's update (`i++`), so the loop never ends (the guard below stops it at five rounds); with
// `for (;;) { break; }` as the inner loop its CodeGenerator fails an assertion instead
// (IllegalStateException "FAILED ASSERTION"). The label, `continue L` (not `break L`, not a bare
// `continue`), the function in the head and the loop inside it are all needed; a `while`
// condition holding the same function, or the function in the body, compile and run correctly.
// The inner loop apparently takes over the label's continue target.
//   interpreted: [0,0,0,0,0]      compiled: crash (RuntimeException: unlocated label)
//   spec: [0,1]
// Found by `oxpecker fuzz` on 1.9.2 (seeded three-hour campaign: `continue L85` in a
// `for (let [a, b] = (() => { ... for (let i ...) {} ... })(); ...)`), reduced by hand, 2026-11-02.
// Replay: oxpecker diff tests/regressions/labelled-for-head-function-loop-continue.js
var r = [];
L: for (var a = function () { while (false) {} }, i = 0; i < 2; i++) {
    r.push(i);
    if (r.length > 4) break;
    continue L;
}
__probe("step", r);
