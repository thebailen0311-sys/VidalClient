#version 330

// Vanta glow: a soft light drawn with additive blending. UV0 runs -1..1 across the quad; Color is the light colour
// (alpha = brightness). A tight white-hot core, a soft halo in the colour, and a faint four-point flare.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec2 q = texCoord0;
    float r2 = dot(q, q);
    if (r2 >= 1.0) discard;
    float core = exp(-r2 * 38.0);
    float halo = exp(-r2 * 6.0) * 0.55 + exp(-r2 * 2.2) * 0.18;
    float flare = (exp(-abs(q.x) * 40.0) * exp(-q.y * q.y * 6.0) + exp(-abs(q.y) * 40.0) * exp(-q.x * q.x * 6.0)) * 0.22;
    float fade = 1.0 - smoothstep(0.7, 1.0, sqrt(r2));
    vec3 c = mix(vertexColor.rgb, vec3(1.0), core * 0.85);
    float a = (core + halo + flare) * fade * vertexColor.a;
    fragColor = vec4(c * a, a) * ColorModulator;
}
