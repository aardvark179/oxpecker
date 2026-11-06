// [array]
var arr0 = [];
// [null]
const v1 = null;
// [symbol]
var f2 = Symbol;
var s3 = "10";
f2.for(s3);
__probe("step", s3, f2, v1, arr0);
// [reassignment]
var arr7 = arr0;
__probe("step", arr7, s3, f2, v1, arr0);
// [simple_for_loop]
for (let [i9] = (() => {
    let n8 = 0;
    return [n8];
})(); (() => {
    const n10 = 6;
    let b11 = i9 < n10;
    return b11;
})(); i9++) {
}
__probe("step", arr7, s3, f2, v1, arr0);
// [prototype_overwrite]
arr0.__proto__ = arr7;
__probe("step", arr7, s3, f2, v1, arr0);
// [builtin_object_instance]
var o17 = new Set();
__probe("step", o17, arr7, s3, f2, v1, arr0);
// [prototype_overwrite]
var o19 = Object.setPrototypeOf(arr7, o17);
__probe("step", o19, o17, arr7, s3, f2, v1);
// [super_property_update]
var o21 = {
    __proto__: arr0,
    m() {
        super.m ||= 0o10000;
    },
};
let s22 = "m";
var v23 = o21[s22]();
__probe("step", s22, o21, o19, o17, arr7, s3);
function f24(p25, p26, p27, p28) {
    Object.defineProperty(arr0, "__proto__", { configurable: true, enumerable: true, set: Object });
    return v1;
}
function f29(p30, p31, p32, p33) {
    o17.clear();
    var arr35 = [1e+21];
    arr7 < Set;
    o19[42] = s22;
    return arr35;
}
for (let i37 = 0; i37 < 100; i37++) {
    const v39 = f24(o19, Object, Set, 0xc98e);
}
for (let i40 = 0; i40 < 100; i40++) {
    var arr42 = f29(arr7, f29, v23, Set);
}
__probe("warm", f29, f24, v23, s22, o21, o19);
let arr43 = f29(arr0, f2, s22, f2);
f24(arr0, f24, Set, 0o10000);
__probe("end", arr43, f29, f24, v23, s22, o21);
