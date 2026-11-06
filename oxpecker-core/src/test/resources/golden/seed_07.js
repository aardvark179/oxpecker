// [undefined]
const v0 = undefined;
// [builtin_object_instance]
var f2 = WeakSet;
new f2();
var arr4 = [];
var arr5 = [-2, 0, 7, 2147483648, 256, 128, -7, 4294967294, 7, 4];
__probe("step", f2, v0);
// [number_computation]
var n7 = 2147483647;
let n8 = 1000000000000.0;
n8 % f2;
const n11 = f2 * (n7 & f2);
var n12 = f2 ** f2;
let n13 = -f2;
__probe("step", n13, n12, n11, n8, n7, arr5);
// [computed_property_assignment]
arr5[n8] = n8;
__probe("step", n13, n12, n11, n8, n7, arr5);
// [computed_property_retrieval]
var v14 = arr4[n12];
__probe("step", v14, n13, n12, n11, n8, n7);
