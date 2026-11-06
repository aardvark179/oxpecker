// [builtin_function]
var f2 = Boolean;
// [string]
var s4 = "";
// [binary]
var n5 = f2 ** parseFloat;
__probe("step", n5, s4, f2);
// [integer]
var n7 = 0b1101111000100001;
__probe("step", n7, n5, s4, f2);
// [java_echo_arith]
const n8 = 0.1;
const n9 = 255;
var n10 = -n9;
var n11 = n8 - n10;
var o12 = J;
var s13 = o12.typeOf(n11);
const s14 = s13;
__probe("step", s14, s13, o12, n11, n10, n9);
// [binary]
o12.__proto__;
let n16 = n11 >> n5;
__probe("step", n16, s14, s13, o12, n11, n10);
// [chained_reassignments]
let n17 = n5;
__probe("step", n17, n16, s14, s13, o12, n11);
// [compare]
let b18 = n10 !== parseFloat;
__probe("step", b18, n17, n16, s14, s13, o12);
