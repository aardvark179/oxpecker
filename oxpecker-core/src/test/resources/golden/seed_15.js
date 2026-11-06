// [builtin_function]
var f1 = Object;
// [null]
var v2 = null;
// [symbol]
let f3 = Symbol;
var v5 = f3.for("true");
__probe("step", v5, f3, v2, f1);
// [super_property_retrieval]
var o11 = {
    __proto__: globalThis,
    m() {
        let v10 = super.set;
    },
};
let s12 = "m";
var v13 = o11[s12]();
__probe("step", s12, o11, v5, f3, v2, f1);
// [integer]
let n14 = 32662;
__probe("step", n14, v13, s12, o11, v5, f3);
function f15(p16, p17) {
    const b18 = delete globalThis[v13];
    var f19 = function () {
        return f3;
    };
    var n21 = 0;
    return f1;
}
for (let i22 = 0; i22 < 10; i22++) {
    const n24 = 2;
    const n25 = i22 % n24;
    var b27 = n25 == 0;
    if (b27) {
    }
    f15(globalThis, "true");
}
__probe("end", f15, n14, v13, s12, o11, v5);
