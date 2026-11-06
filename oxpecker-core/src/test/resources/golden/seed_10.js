// [undefined]
let v0 = undefined;
// [builtin_object_instance]
var o2 = new Date();
// [repeat_loop]
for (let i4 = 0; i4 < 39; i4++) {
}
__probe("step", o2, v0);
// [super_property_assignment]
var o8 = {
    __proto__: o2,
    m() {
        super[42] = 1073741824n;
    },
};
const v10 = o8["m"]();
__probe("step", v10, o8, o2, v0);
// [number_computation]
let o11 = Math;
var n13 = 0.0;
let b14 = !n13;
var n15 = o11.round(10);
o11.round(10);
let n17 = o8 % 10;
__probe("step", n17, n15, b14, n13, o11, v10);
// [reassignment]
var n18 = n15;
n18 = 10;
__probe("step", n18, n17, n15, b14, n13, o11);
// [float]
const n19 = 4.5545403515625e5;
__probe("step", n19, n18, n17, n15, b14, n13);
// [do_while_loop]
var n21 = 0;
do {
    n21++;
} while ((() => {
    var n24 = 7;
    return n21 < n24;
})());
__probe("step", n21, n19, n18, n17, n15, b14);
n13 >> 0;
