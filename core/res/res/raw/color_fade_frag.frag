#extension GL_OES_EGL_image_external : require

precision mediump float;
uniform samplerExternalOES texUnit;
uniform float opacity;
varying vec2 UV;

vec3 mixBvec3(vec3 a, vec3 b, bvec3 sel) {
    return vec3(
        sel.x ? b.x : a.x,
        sel.y ? b.y : a.y,
        sel.z ? b.z : a.z
    );
}

vec3 srgbTransfer(vec3 c) {
    vec3 gamma = 1.055 * pow(c, vec3(1.0/2.4)) - 0.055;
    vec3 linear = 12.92 * c;
    bvec3 selectParts = lessThan(c, vec3(0.0031308));
    return mixBvec3(gamma, linear, selectParts);
}

vec3 srgbTransferInv(vec3 c) {
    vec3 gamma = pow((c + 0.055)/1.055, vec3(2.4));
    vec3 linear = c / 12.92;
    bvec3 selectParts = lessThan(c, vec3(0.04045));
    return mixBvec3(gamma, linear, selectParts);
}

void main() {
    vec3 inRgb = srgbTransferInv(texture2D(texUnit, UV).rgb);
    vec3 fade = inRgb * opacity * opacity;
    vec3 outRgb = srgbTransfer(fade);

    gl_FragColor = vec4(outRgb, 1.0);
}
