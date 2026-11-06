// [symbol]
const f0 = Symbol;
var s1 = "é";
var v2 = f0(s1);
// [string]
let s3 = "NaN";
// [builtin_object_instance]
let o5 = new Set();
// [computed_property_retrieval]
var v6 = o5[v2];
__probe("step", v6, o5, s3, v2, s1, f0);
// [binary]
var v7 = v6 % Set;
__probe("step", v7, v6, o5, s3, v2, s1);
// [number_computation]
const n9 = 3;
const n11 = +Set;
var n12 = -n11;
let n14 = Math.sqrt(n12) ** Set;
const n15 = +Set;
var n16 = Math.imul(n15, n9);
+n16;
__probe("step", n16, n15, n14, n12, n11, n9);
// [computed_super_property]
var o19 = {
    __proto__: o5,
    m() {
        super[o5] = 1.7976931348623157e+308;
    },
};
o19["m"]();
__probe("step", o19, n16, n15, n14, n12, n11);
// [super_property_update]
var o23 = {
    __proto__: o19,
    m() {
        super.foo |= n16;
    },
};
let v25 = o23["m"]();
__probe("step", v25, o23, o19, n16, n15, n14);
// [prototype_overwrite]
o23.__proto__ = Math;
__probe("step", v25, o23, o19, n16, n15, n14);
// [property_configuration]
Object.defineProperty(o5, "b", { value: o5 });
__probe("step", v25, o23, o19, n16, n15, n14);
// [element_update]
o19[6] /= 1.7976931348623157e+308;
__probe("step", v25, o23, o19, n16, n15, n14);
