// [boolean]
var b2 = true;
const b3 = true;
// [string]
var s4 = "D.𣧵)`";
// [typed_array]
var o7 = new Float64Array(35);
__probe("step", o7, s4, b3, b2);
// [property_retrieval]
let v8 = o7.__proto__;
__probe("step", v8, o7, s4, b3, b2);
// [reassignment]
let n9 = 0b11111111001010;
__probe("step", n9, v8, o7, s4, b3, b2);
