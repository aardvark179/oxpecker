// [integer]
var n0 = 65127;
// [null]
var v1 = null;
__probe("step", v1, n0);
// [builtin_object_instance]
var f4 = Date;
var o5 = new f4();
// [for_in_loop]
for (let i6 in o5) {
}
__probe("step", o5, f4, v1, n0);
// [unary]
var n8 = 2147483648;
n8++;
__probe("step", n8, o5, f4, v1, n0);
// [property_assignment]
var t11 = Object.getOwnPropertyNames(o5);
t11.length = n8;
__probe("step", t11, n8, o5, f4, v1, n0);
// [element_assignment]
o5[5] = v1;
__probe("step", t11, n8, o5, f4, v1, n0);
// [number_computation]
let n15 = ~o5;
__probe("step", n15, t11, n8, o5, f4, v1);
function f17(p18, p19, p20) {
    var n22 = 0;
    return Math;
}
for (let i23 = 0; i23 < 10; i23++) {
    let n25 = 6;
    if (i23 == n25) {
        const o30 = new BigInt64Array(64);
        continue;
    }
    const o31 = f17(f4, n0, i23);
}
__probe("end", f17, n15, t11, n8, o5, f4);
