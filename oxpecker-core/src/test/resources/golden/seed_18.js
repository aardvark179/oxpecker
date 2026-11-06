// [boolean]
let b0 = false;
// [string]
const s2 = "";
// [builtin_object_instance]
const f3 = WeakMap;
var o4 = new f3();
// [float]
let n5 = 96860.32421875;
// [trivial_function]
var f6 = function () {
    return 10;
};
__probe("step", f6, n5, o4, f3, s2, b0);
// [number_to_string]
var n7 = 16;
const s8 = n5.toPrecision(n7);
__probe("step", s8, n7, f6, n5, o4, f3);
// [reassignment]
f6 = f3;
__probe("step", s8, n7, f6, n5, o4, f3);
// [number_computation]
b0 & o4;
const o10 = Math;
let n11 = 1;
const n12 = +f3;
var n13 = +n12;
__probe("step", n13, n12, n11, o10, s8, n7);
// [property_assignment]
o10.LOG2E = n5;
__probe("step", n13, n12, n11, o10, s8, n7);
function f14(p15, p16, p17, p18) {
    var n21 = 255;
    var n22 = ~10;
    let n23 = n21 ** n21;
    var n24 = n22 | n23;
    let n25 = Math.round(p15);
    +p15;
    o10.round(10);
    var f28 = function () {
        return n24;
    };
    var n29 = 0;
    var n30 = n29;
    return n7;
}
for (let i31 = 0; i31 < 100; i31++) {
    if (i31 == 2) {
        var n36 = n13 >> s8;
        let f37 = o4.has;
    }
    const n38 = f14(o4, n7, s2, o10);
}
__probe("end", f14, n13, n12, n11, o10, s8);
