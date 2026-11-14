#version 330

// Vanta post-processing over the rendered world (Sampler0 = this frame's copy). Per vertex:
//   Color  = (bloom, saturation / 2, vignette, grade strength), 0..1 each
//   UV0.x  = time in seconds (grain), UV0.y = grade preset (integer part) + accent hue / 360 (fraction)

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

vec3 hsl2rgb(vec3 c) {
    vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
    return c.z + c.y * (rgb - 0.5) * (1.0 - abs(2.0 * c.z - 1.0));
}

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

vec3 bright(vec2 uv) {
    vec3 c = texture(Sampler0, uv).rgb;
    float l = max(c.r, max(c.g, c.b));
    return c * smoothstep(0.55, 1.0, l);
}

void main() {
    vec2 tex = vec2(textureSize(Sampler0, 0));
    vec2 uv = gl_FragCoord.xy / tex;
    float bloomK = vertexColor.r, sat = vertexColor.g * 2.0, vig = vertexColor.b, gradeK = vertexColor.a;
    float time = texCoord0.x;
    float preset = floor(texCoord0.y);
    float hue = fract(texCoord0.y);

    // Faint chromatic aberration toward the screen edges.
    vec2 cc = uv - 0.5;
    vec2 ca = cc * dot(cc, cc) * 0.012;
    vec3 col = vec3(texture(Sampler0, uv + ca).r, texture(Sampler0, uv).g, texture(Sampler0, uv - ca).b);

    // Bloom: bright parts spread into soft light, sampled on two rings.
    if (bloomK > 0.001) {
        vec3 b = vec3(0.0);
        for (int i = 0; i < 12; i++) {
            float a = float(i) * 0.5236;
            vec2 d = vec2(cos(a), sin(a));
            b += bright(uv + d * 5.0 / tex) * 0.6 + bright(uv + d * 13.0 / tex) * 0.4 + bright(uv + d * 26.0 / tex) * 0.25;
        }
        col += b / 12.0 * bloomK * 1.4;
    }

    // Saturation / vibrance (HSL-style: weaker colours gain more than already-vivid ones).
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    float vivid = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
    col = mix(vec3(luma), col, mix(sat, 1.0 + (sat - 1.0) * 0.5, vivid));

    // Colour grades.
    vec3 graded = col;
    if (preset == 1.0) {        // Cinematic: teal shadows, warm highlights
        graded = mix(col * vec3(0.86, 1.02, 1.08), col * vec3(1.1, 1.0, 0.86), smoothstep(0.2, 0.8, luma));
    } else if (preset == 2.0) { // Dream: shadows toward the theme accent, lifted soft highlights
        vec3 tint = hsl2rgb(vec3(hue, 0.65, 0.55));
        graded = mix(col, col * tint * 1.6, (1.0 - luma) * 0.55) + vec3(0.03);
    } else if (preset == 3.0) { // Noir: deep contrast black and white
        float l = smoothstep(0.05, 0.95, luma);
        graded = vec3(l * l * (3.0 - 2.0 * l));
    } else if (preset == 4.0) { // Neon: hue pushed toward magenta/cyan, punchy contrast
        graded = mix(col, vec3(col.r * 1.15, col.g * 0.9, col.b * 1.25), 0.8);
        graded = (graded - 0.5) * 1.15 + 0.5;
    }
    col = mix(col, graded, gradeK);

    // Vignette and film grain.
    col *= 1.0 - vig * smoothstep(0.35, 0.95, length(cc * vec2(1.25, 1.0)) * 1.15);
    col += (hash(gl_FragCoord.xy + fract(time * 7.13) * 100.0) - 0.5) * 0.025;

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0) * ColorModulator;
}
