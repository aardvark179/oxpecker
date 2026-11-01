// Rhino 1.9.2: the `__proto__` accessor on a LiveConnect wrapper of a Java array (`J.intArray(2)`)
// crashes both modes with java.lang.ClassCastException "NativeJavaArray cannot be cast to
// ScriptableObject", which the script cannot catch: writing `a.__proto__ = {}` fails in
// NativeObject.js_protoSetter; `Object.setPrototypeOf(a, {})` itself succeeds, but a later read of
// `a.__proto__` fails in NativeObject.js_protoGetter. The wrapper of a plain Java object
// (`J.__proto__ = {}`) throws a catchable InternalError instead. The fuzzer's forms set the array's prototype to itself or to a JS array
// built from it. Same cast family as compiled-with-java-object-name-lookup-classcast.js (Java
// wrappers implement Scriptable directly, not ScriptableObject). Not checked on master b95f75d.
//   interpreted: crash (ClassCastException)     compiled: crash (ClassCastException)
// Found by `oxpecker fuzz` on 1.9.2 (three-hour campaign: `o8.__proto__ = v9` with `v9` the same
// Java array, and with an array of its length/type/sum), 2026-11-01.
// Replay: oxpecker diff tests/regressions/java-array-set-prototype-classcast.js
var a = J.intArray(2);
var r = [];
try { a.__proto__ = {}; r.push("ok"); } catch (e) { r.push(e.name); }
__probe("step", r);
