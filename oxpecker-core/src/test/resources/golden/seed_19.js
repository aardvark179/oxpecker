// [boolean]
let b0 = true;
// [string]
let s1 = "򕼊\"vq\u000cu";
// [super_property_assignment]
var o4 = {
    m() {
        super.g = s1;
    },
};
var v6 = o4["m"]();
__probe("step", v6, o4, s1, b0);
// [chained_reassignments]
let o7 = o4;
__probe("step", o7, v6, o4, s1, b0);
// [property_retrieval]
var v8 = o4.d;
__probe("step", v8, o7, v6, o4, s1, b0);
// [for_of_loop]
for (let i9 of v8) {
}
__probe("step", v8, o7, v6, o4, s1, b0);
// [regexp]
var o11 = /(?=a)/s;
__probe("step", o11, v8, o7, v6, o4, s1);
function f12(p13, p14, p15, p16) {
    p13 = o4;
    var o17 = {};
    var o18 = {};
    var o19 = {};
    var o20 = {};
    o17.foo = b0;
    o18.foo = p15;
    o18.a = v6;
    o19.foo = s1;
    o19.a = v6;
    o19.m = b0;
    o20.foo = p15;
    o20.a = o20;
    o20.y = o11;
    const n22 = 55;
    var o23 = new Uint8Array(n22);
    return o19;
}
function f24(p25, p26) {
    p25.constructor = v6;
    o4.valueOf = v6;
    var n27 = 0o74566;
    return p25;
}
for (let i28 = 0; i28 < 10; i28++) {
    f12("𻊥", "𻊥", o4, v6);
}
for (let i31 = 0; i31 < 100; i31++) {
    let o33 = f24(o7, v6);
}
__probe("warm", f24, f12, o11, v8, o7, v6);
f24(o11, v8);
f12(f24, "m", f12, o11);
