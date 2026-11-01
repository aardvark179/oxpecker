// Rhino 1.9.2: in compiled mode a function with a rest parameter that is also called by name from
// inside another function (which makes it an optimizer direct-call target) binds the rest
// parameter to the first extra argument itself instead of an array of all the extra arguments.
// It then holds for every call of the function, not only the one inside the other function:
// top-level calls, `f.call(...)`, calls through an alias or a property. Extra arguments past the
// first are lost, a non-array argument arrives unwrapped (`rest === 7`), and an array argument
// arrives as the caller's own array, so `rest.length = 0` empties it. The interpreter, and the
// same function when no other function calls it (`k` below), build the array as the spec says.
//   interpreted: [7] | [7] | [7,8] | [7]      compiled: 7 | 7 | 7 | [7]
// Found by `oxpecker fuzz` on 1.9.2 (three-hour campaign, a helper with `...p20` called from a
// warmed function whose `p20.length = 0` emptied a global array), reduced by hand, 2026-11-01.
// Replay: oxpecker diff tests/regressions/compiled-rest-param-direct-call-target.js
function f(a, ...rest) { return rest; }
function g() { return f(0, 7); }
function k(a, ...rest) { return rest; }
__probe("step", f(0, 7), g(), f(0, 7, 8), k(0, 7));
