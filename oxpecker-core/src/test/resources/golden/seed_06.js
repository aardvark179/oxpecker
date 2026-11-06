// [undefined]
Symbol.for("é");
let v4 = undefined;
// [builtin_object_instance]
let o6 = new WeakMap();
// [computed_property_retrieval]
const v7 = o6[undefined];
__probe("step", v7, o6, v4);
// [super_method_call]
var o10 = {
    __proto__: o6,
    m() {
        super.valueOf(undefined, "é");
    },
};
const s11 = "m";
const v12 = o10[s11]();
__probe("step", v12, s11, o10, v7, o6, v4);
// [computed_property_retrieval]
let v13 = o10[s11];
__probe("step", v13, v12, s11, o10, v7, o6);
// [builtin_object_instance]
let f14 = Date;
new f14();
__probe("step", f14, v13, v12, s11, o10, v7);
// [element_retrieval]
let v16 = o10[6];
__probe("step", v16, f14, v13, v12, s11, o10);
// [update]
"é" != v16;
let v18 = o6;
v18 -= v12;
__probe("step", v18, v16, f14, v13, v12, s11);
