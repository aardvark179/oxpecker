// [symbol]
var f1 = Symbol;
var v2 = f1.hasInstance;
// [binary]
let v3 = v2 | null;
__probe("step", v3, v2, f1);
// [object_hierarchy]
var o4 = {};
var o5 = {};
var o6 = {};
var o7 = {};
o4.e = v3;
o5.e = v3;
o5["!"] = o7;
o6.e = f1;
o6["!"] = v3;
o6.f = null;
o7.e = o4;
o7["!"] = o7;
o7.d = f1;
__probe("step", o7, o6, o5, o4, v3, v2);
// [try_catch_finally]
try {
} catch (e8) {
    e8 += v3;
    let n9 = 4;
} finally {
    var o12 = {
        __proto__: o7,
        m() {
            super.y;
        },
    };
    var s13 = "m";
    let v14 = o12[s13]();
    const v15 = o7[3];
}
__probe("step", o7, o6, o5, o4, v3, v2);
// [binary]
let v16 = v2 || o7;
__probe("step", v16, o7, o6, o5, o4, v3);
// [super_property_assignment]
var o18 = {
    __proto__: o6,
    m() {
        super["!"] = o4;
    },
};
o18["m"]();
__probe("step", o18, v16, o7, o6, o5, o4);
// [number_computation]
let o21 = Math;
let n24 = +64;
const n25 = ~o7;
const n26 = o21.trunc(64);
__probe("step", n26, n25, n24, o21, o18, v16);
// [typed_array]
var f27 = Uint8Array;
var n28 = 41;
new f27(n28);
__probe("step", n28, f27, n26, n25, n24, o21);
