// [float]
let n1 = 1000000000.0;
// [string]
var s2 = "";
// [chained_reassignments]
let s4 = ")";
__probe("step", s4, s2, n1);
// [typed_array]
var o8 = new BigUint64Array(0);
__probe("step", o8, s4, s2, n1);
// [property_assignment]
o8.f = n1;
__probe("step", o8, s4, s2, n1);
// [number_computation]
const o9 = Math;
let n12 = -3;
var n13 = ~n12;
__probe("step", n13, n12, o9, o8, s4, s2);
// [for_in_loop]
for (var i14 in o8) {
    var o17 = {
        __proto__: o8,
        m() {
            super.then = 1e-15;
        },
    };
    const s18 = "m";
    let v19 = o17[s18]();
}
__probe("step", n13, n12, o9, o8, s4, s2);
// [int_array]
var arr20 = [2147483647, 3221225471, 5];
__probe("step", arr20, n13, n12, o9, o8, s4);
// [computed_property_assignment]
o9[arr20] = 1e-15;
__probe("step", arr20, n13, n12, o9, o8, s4);
function f21(p22, p23, p24) {
    let arr26 = [p23, n1];
    var n28 = 0;
    return n28;
}
for (let i29 = 0; i29 < 10; i29++) {
    var n31 = 4;
    var b32 = i29 == n31;
    if (b32) {
        var o34 = {};
        var o35 = {};
        var o36 = {};
        var o37 = {};
        o34.f = o36;
        o35.f = BigUint64Array;
        o35.d = o35;
        o36.f = b32;
        o36.d = o37;
        o36.b = o37;
        o37.f = i29;
        o37.d = o35;
        o37.g = 3;
        o35[3] = o9;
    }
    f21(n12, arr20, f21);
}
__probe("end", f21, arr20, n13, n12, o9, o8);
