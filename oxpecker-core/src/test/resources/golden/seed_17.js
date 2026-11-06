// [bigint]
const big0 = 4294967296n;
// [array]
var arr1 = [big0, big0, big0, big0];
// [string]
const s2 = "length";
// [integer]
let n4 = 49005;
// [typed_array]
var f5 = Int8Array;
var n6 = 39;
var o7 = new f5(n6);
__probe("step", o7, n6, f5, n4, s2, arr1);
// [computed_property_assignment]
o7[arr1] = big0;
__probe("step", o7, n6, f5, n4, s2, arr1);
function f9(p10, p11, p12, p13) {
    delete p11.toString;
    let n16 = 0;
    var n17 = n16;
    do {
        p13 &&= arr1;
        let n19 = n17++;
    } while ((() => {
        var b21 = n17 < 0;
    return b21;
})());
    p11 >>>= p11;
    return f5;
}
function f22(p23, p24) {
    var o27 = {
        __proto__: o7,
        m() {
            super.hasOwnProperty(big0);
        },
    };
    var v29 = o27["m"]();
    let arr30 = [-10, 1, -9];
    p23 += f5;
    let v31 = v29[-2];
    return p24;
}
for (let i32 = 0; i32 < 100; i32++) {
    const f34 = f9(f22, o7, f5, o7);
}
for (let i35 = 0; i35 < 10; i35++) {
    f22(f5, isNaN);
}
__probe("warm", f22, f9, o7, n6, f5, n4);
const f39 = f9(f22, arr1, f22(f22, f5), arr1);
__probe("end", f39, f22, f9, o7, n6, f5);
