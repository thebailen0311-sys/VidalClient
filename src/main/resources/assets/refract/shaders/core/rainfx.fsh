#version 330

// Vanta rain over the frame copy (Sampler0). UV0 = (time, intensity); Color.r = camera pitch lean (0.5 = level).
// Falling streaks act as thin water lenses (they bend what is behind them and catch a glint); drops beaded on the
// lens slide down and show the scene refracted upside-down through them, like real water on glass.

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

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

void main() {
    vec2 tex = vec2(textureSize(Sampler0, 0));
    vec2 uv = gl_FragCoord.xy / tex;
    float time = texCoord0.x, k = texCoord0.y;
    float lean = (vertexColor.r - 0.5) * 0.5;
    vec2 off = vec2(0.0);
    float glint = 0.0;

    // Falling streaks, three depths.
    vec2 p = gl_FragCoord.xy / 900.0;
    for (int layer = 0; layer < 3; layer++) {
        float fl = float(layer), sc = 1.0 + fl * 0.85;
        vec2 q = p * vec2(70.0 * sc, 2.6 * sc);
        q.x += q.y * lean * 0.1;
        float col = floor(q.x), seed = hash(vec2(col, fl * 7.0));
        if (seed < 0.6) continue;
        float y = fract(q.y * 0.35 + time * (2.6 + seed * 1.5 + fl * 0.5) + seed * 9.0);
        float body = smoothstep(0.0, 0.05, y) * smoothstep(0.26, 0.08, y);
        float x = fract(q.x) - 0.5;
        float w = 0.09 - fl * 0.02;
        float s = body * (1.0 - smoothstep(0.0, w, abs(x)));
        off += vec2(-x * 2.0, 0.6) * s * (0.006 - fl * 0.0015);
        glint += s * smoothstep(w * 0.6, 0.0, abs(x + w * 0.35)) * (0.5 - fl * 0.12);
    }

    // Drops on the lens: a sparse grid of cells, each with one bead that slowly slides down and leaves a trail.
    vec2 g = gl_FragCoord.xy / tex.y * vec2(9.0, 5.0);
    vec2 cell = floor(g), f = fract(g) - 0.5;
    float h = hash(cell);
    float dropMask = 0.0;
    if (h > 0.55) {
        float slide = fract(time * (0.05 + h * 0.06) + h * 7.0);
        vec2 c = vec2((h - 0.775) * 1.4, 0.45 - slide * 0.9);
        vec2 d = (f - c) * vec2(1.0, 1.25);
        float r = 0.06 + 0.06 * fract(h * 13.0);
        float dist = length(d);
        if (dist < r) {
            float z = sqrt(1.0 - (dist * dist) / (r * r));
            off += -d / r * z * 0.04;          // magnify and flip, like a real drop
            glint += smoothstep(0.35, 0.0, length(d / r - vec2(-0.35, 0.4))) * 0.7;
            dropMask = 1.0;
        }
        float trail = smoothstep(0.02, 0.0, abs(f.x - c.x)) * step(c.y, f.y) * smoothstep(0.5, 0.0, f.y - c.y);
        off += vec2(0.0, 0.004) * trail;
    }

    vec3 col = texture(Sampler0, uv + off * k).rgb;
    col += vec3(0.85, 0.92, 1.0) * glint * k;
    col *= 1.0 - dropMask * 0.06 * k;          // a drop's rim reads slightly darker
    float a = clamp(length(off) * 160.0 + glint, 0.0, 1.0) * k;
    fragColor = vec4(col, a) * ColorModulator;
}
