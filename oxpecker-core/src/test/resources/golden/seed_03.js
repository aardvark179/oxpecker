// [builtin_object_instance]
var o1 = new Object();
// [string]
var s2 = "󛠰";
// [element_retrieval]
var v5 = o1[5];
__probe("step", v5, s2, o1);
// [reassignment]
var o6 = o1;
o6 = o1;
__probe("step", o6, v5, s2, o1);
// [number_computation]
Object != s2;
Math.round(s2);
!Math.floor(o6);
let v13 = s2 ?? 256;
~v13;
__probe("step", v13, o6, v5, s2, o1);
// [float]
const n15 = 5.85260353515625e5;
__probe("step", n15, v13, o6, v5, s2, o1);
