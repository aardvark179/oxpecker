// [float]
let n1 = 1e+21;
// [super_property_update]
var o5 = {
    m() {
        super.done %= 670009.455078125;
    },
};
var s6 = "m";
var v7 = o5[s6]();
__probe("step", v7, s6, o5, n1);
// [property_update]
o5.e %= 670009.455078125;
__probe("step", v7, s6, o5, n1);
o5.__proto__;
