// [symbol]
var f0 = Symbol;
let s1 = "😀";
const v2 = f0(s1);
// [builtin_function]
const f3 = isNaN;
// [bigint]
let big4 = 255n;
// [builtin_object_instance]
let f5 = Object;
var o6 = new f5();
// [element_assignment]
o6[9] = o6;
__probe("step", o6, f5, big4, f3, v2, s1);
// [binary]
const n7 = f3 << f0;
__probe("step", n7, o6, f5, big4, f3, v2);
// [chained_reassignments]
var o8 = o6;
__probe("step", o8, n7, o6, f5, big4, f3);
