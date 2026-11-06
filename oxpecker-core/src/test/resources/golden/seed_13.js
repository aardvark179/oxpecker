// [float]
const n0 = 1e0;
// [integer]
const n2 = 0b1101110100100100;
// [binary]
const n3 = 0x73f5 << 0x73f5;
__probe("step", n3, n2, n0);
// [super_property_retrieval]
var o6 = {
    m() {
        super.value;
    },
};
var v8 = o6["m"]();
__probe("step", v8, o6, n3, n2, n0);
// [callback_property]
o6.toString = v8;
__probe("step", v8, o6, n3, n2, n0);
// [integer]
let n9 = 0b111110010000;
__probe("step", n9, v8, o6, n3, n2, n0);
function f10(p11, p12) {
    p11[9] = n0;
    var n13 = 0o172441;
    p12 = p11;
    const n14 = 0;
    var n15 = n14;
    return n14;
}
for (let i16 = 0; i16 < 100; i16++) {
    const n18 = 2;
    const n19 = i16 % n18;
    let n20 = 0;
    const b21 = n19 == n20;
    if (b21) {
        const arr23 = [-5, -4, 2147483648];
        o6.__proto__ = arr23;
    }
    f10(o6, v8);
}
__probe("end", f10, n9, v8, o6, n3, n2);
