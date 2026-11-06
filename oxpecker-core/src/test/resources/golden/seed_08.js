// [symbol]
let f1 = Symbol;
var s2 = "😀";
let v3 = f1(s2);
// [builtin_object_instance]
var n4 = 13;
let f5 = Array;
var arr6 = new f5(n4);
const n7 = 4;
let f8 = Array;
new f8(n7);
new WeakMap();
__probe("step", f8, n7, arr6, f5, n4, v3);
// [int_array]
arr6.reduce(f5, n7);
const arr15 = [10, 2147483646, 3, 3, 1073741824, 4096, 5];
__probe("step", arr15, f8, n7, arr6, f5, n4);
// [binary]
let n16 = 0b1111111111111111111111111111110 & n7;
__probe("step", n16, arr15, f8, n7, arr6, f5);
