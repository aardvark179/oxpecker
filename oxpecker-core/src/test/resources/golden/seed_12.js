// [array]
var arr1 = [0b1000000];
// [number_computation]
const n3 = 127;
var n6 = +9007199254740992.0;
var b7 = !arr1;
const n8 = Math.trunc(0);
!arr1;
Math.imul(n8, 0);
const n11 = Math.pow(arr1, n3);
__probe("step", n11, n8, b7, n6, n3, arr1);
// [prototype_overwrite]
delete Math[0];
const f13 = Object;
let o14 = new f13();
Math.__proto__ = o14;
__probe("step", o14, f13, n11, n8, b7, n6);
var o16 = new Object();
const f17 = Object;
var o18 = f17.setPrototypeOf(o14, o16);
__probe("step", f17, o16, o14, f13, n11, n8);
// [float_array]
const arr19 = [2.0, 1e-15, 0.1, 0.1, 5e-324];
__probe("step", arr19, o18, f17, o16, o14, f13);
function f20(p21, p22, p23) {
    var f24 = function (p25, p26) {
        return n3;
    };
    let n27 = f24(o14, arr1);
    switch (b7) {
        case n3:
            arr1[5];
            const n30 = 0b11111111111111111111111111111110;
            break;
        case n11:
            arr19.has = o14;
            break;
        case n6:
            for (let i31 in o18) {
            }
            for (let i33 in p21) {
            }
            break;
        default:
            let v35 = Math[2];
            var o37 = new Map();
            break;
    }
    const n38 = 0;
    var n39 = n38;
    return o16;
}
for (let i40 = 0; i40 < 10; i40++) {
    let b43 = i40 == 4;
    if (b43) {
    }
    f20(o14, 0b1000000, arr19);
}
__probe("end", f20, arr19, o18, f17, o16, o14);
