#version 330

// Vanta space sky, drawn only on sky pixels (Sampler1 = world depth copied before the hand; 1.0 = sky).
// Sampler0 = colour copy of the frame. Per vertex: UV0 = view direction x,y (world space), Color.b = sign of z,
// Color.rg = time * 20 (16 bit, constant), Color.a = scene * 32 (1 planets, 2 black hole, 3 nebula, 4 galaxy).

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }

float noise(vec3 x) {
    vec3 i = floor(x), f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x), mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x), mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) { v += a * noise(p); p = p * 2.03 + 1.7; a *= 0.5; }
    return v;
}

void basis(vec3 c, out vec3 t, out vec3 b) {
    t = normalize(cross(abs(c.y) > 0.95 ? vec3(1, 0, 0) : vec3(0, 1, 0), c));
    b = cross(c, t);
}

vec3 stars(vec3 d, float time) {
    vec3 col = vec3(0.0);
    for (int layer = 0; layer < 2; layer++) {
        float sc = layer == 0 ? 180.0 : 420.0;
        vec3 p = d * sc, c = floor(p), f = fract(p) - 0.5;
        float h = hash(c + float(layer) * 13.0);
        if (h > (layer == 0 ? 0.985 : 0.992)) {
            float tw = 0.65 + 0.35 * sin(time * (2.0 + h * 5.0) + h * 60.0);
            float s = smoothstep(0.42, 0.0, length(f)) * tw;
            vec3 tint = mix(vec3(0.75, 0.85, 1.0), vec3(1.0, 0.85, 0.7), fract(h * 91.0));
            col += tint * s * (layer == 0 ? 1.3 : 0.7);
        }
    }
    return col;
}

vec3 nebula(vec3 d, float time) {
    float n = fbm(d * 2.6 + vec3(0.0, time * 0.004, 0.0));
    float m = fbm(d * 5.1 - vec3(time * 0.003));
    float band = exp(-pow(dot(d, normalize(vec3(0.3, 0.8, -0.5))) * 2.4, 2.0)); // a galactic band across the sky
    vec3 a = vec3(0.45, 0.15, 0.75), b = vec3(0.05, 0.55, 0.85), c = vec3(0.95, 0.35, 0.55);
    vec3 col = mix(a, b, smoothstep(0.35, 0.75, n)) + c * smoothstep(0.62, 0.85, m) * 0.6;
    return col * smoothstep(0.3, 0.8, n) * (0.25 + 0.75 * band) * 0.55;
}

// A lit planet with optional rings. Returns colour; alpha (coverage) in .a
vec4 planet(vec3 d, vec3 c, float R, vec3 base, vec3 alt, float seed, bool rings, vec3 sun, float time) {
    vec3 t, b; basis(c, t, b);
    float ca = dot(d, c);
    if (ca < 0.0) return vec4(0.0);
    vec2 q = vec2(dot(d, t), dot(d, b)) / sin(R);
    // Tilted axis: the whole planet (bands and rings) is rotated, so it reads as a 3D body, not a flat badge.
    float tilt = 0.42 + seed * 0.07;
    q = mat2(cos(tilt), -sin(tilt), sin(tilt), cos(tilt)) * q;
    float r = length(q);
    vec4 outc = vec4(0.0);
    if (rings) {
        vec2 rq = vec2(q.x, q.y / 0.28);
        float rr = length(rq);
        if (rr > 1.35 && rr < 2.3 && (q.y < 0.0 || r > 1.0)) {
            float bands = 0.6 + 0.4 * sin(rr * 38.0 + seed);
            float fade = smoothstep(1.35, 1.45, rr) * smoothstep(2.3, 2.1, rr);
            outc = vec4(mix(base, vec3(1.0), 0.35) * bands, 0.75 * fade);
        }
    }
    if (r < 1.0) {
        vec3 n = normalize(t * q.x + b * q.y + c * sqrt(1.0 - r * r) * -1.0);
        n = normalize(vec3(q.x, q.y, sqrt(1.0 - r * r)));
        vec3 L = normalize(vec3(dot(sun, t), dot(sun, b), dot(sun, -c)));
        float lit = clamp(dot(n, L) * 0.9 + 0.12, 0.0, 1.0);
        // Surface wraps around the sphere and turns slowly (longitude from the sphere point, not the flat disc).
        float lon = atan(q.x, n.z) + time * 0.05;
        float s = fbm(vec3(lon * 2.2, q.y * 7.0, seed));
        float storm = smoothstep(0.08, 0.0, length(vec2(lon - 1.2 - time * 0.05, q.y + 0.25) * vec2(0.6, 1.4)) - 0.1);
        vec3 surf = vec3(0.0);
        surf = mix(base, alt, smoothstep(0.35, 0.7, s)) + vec3(0.9, 0.5, 0.35) * storm * 0.4;
        float rim = pow(1.0 - n.z, 3.0);
        vec3 col = surf * lit + mix(base, vec3(1.0), 0.4) * rim * 0.6 * (0.3 + lit);
        float edge = smoothstep(1.0, 0.985, r);
        outc = vec4(mix(outc.rgb, col, edge), max(outc.a, edge));
    } else {
        float glow = exp(-(r - 1.0) * 14.0) * 0.5;
        outc.rgb += mix(base, vec3(1.0), 0.5) * glow;
        outc.a = max(outc.a, glow * 0.8);
    }
    return outc;
}

void main() {
    vec2 tex = vec2(textureSize(Sampler0, 0));
    vec2 uv = gl_FragCoord.xy / tex;
    if (texture(Sampler1, uv).r < 0.99999) discard; // not sky: terrain, mobs and clouds stay in front
    vec3 d = vec3(texCoord0, 0.0);
    d.z = sqrt(max(1.0 - dot(d.xy, d.xy), 0.0)) * (vertexColor.b > 0.5 ? 1.0 : -1.0);
    d = normalize(d);
    float time = (floor(vertexColor.r * 255.0 + 0.5) * 256.0 + floor(vertexColor.g * 255.0 + 0.5)) / 20.0;
    int scene = int(vertexColor.a * 255.0 / 32.0 + 0.5);
    vec3 base = texture(Sampler0, uv).rgb;
    float dark = 1.0 - clamp(dot(base, vec3(0.299, 0.587, 0.114)) * 1.5, 0.0, 1.0);
    vec3 sun = normalize(vec3(-0.4, 0.5, 0.75));

    // Black hole: bends everything behind it (stars and nebula are looked up along the bent ray).
    vec3 bh = normalize(vec3(-0.47, 0.57, 0.67));
    float Rs = 0.08;
    vec3 ld = d;
    float ang = acos(clamp(dot(d, bh), -1.0, 1.0));
    bool hole = scene == 2 || scene == 4 || scene == 5;
    if (hole && ang < 0.6) {
        float bendAng = Rs * Rs * 2.2 / max(ang, Rs * 0.5);
        vec3 axis = normalize(cross(bh, d) + 1e-5);
        ld = normalize(d * cos(-bendAng) + cross(axis, d) * sin(-bendAng));
    }

    vec3 add = stars(ld, time) * dark;
    if (scene >= 3) add += nebula(ld, time) * (0.35 + 0.65 * dark) * (scene == 5 ? vec3(1.25, 0.7, 1.4) : vec3(1.0));
    vec3 col = base + add;

    if (scene == 1 || scene == 4) {
        vec4 p1 = planet(d, normalize(vec3(0.5, 0.64, -0.58)), 0.11, vec3(0.85, 0.45, 0.25), vec3(0.55, 0.2, 0.15), 3.0, true, sun, time);
        vec4 p2 = planet(d, normalize(vec3(-0.3, 0.22, -0.93)), 0.045, vec3(0.35, 0.65, 0.95), vec3(0.85, 0.95, 1.0), 7.0, false, sun, time);
        vec4 p3 = planet(d, normalize(vec3(0.9, 0.12, 0.42)), 0.03, vec3(0.75, 0.75, 0.8), vec3(0.4, 0.4, 0.45), 11.0, false, sun, time);
        col = mix(col, p3.rgb, p3.a);
        col = mix(col, p2.rgb, p2.a);
        col = mix(col, p1.rgb, p1.a);
    }
    if (hole) {
        vec3 t, b; basis(bh, t, b);
        vec2 q = vec2(dot(d, t), dot(d, b));
        float dt = -0.35;
        q = mat2(cos(dt), -sin(dt), sin(dt), cos(dt)) * q; // the disk is tilted, like a real one seen at an angle
        // Accretion disk (tilted), brighter on the side spinning toward you, swirling.
        vec2 dq = vec2(q.x, q.y / 0.22);
        float e = length(dq) / Rs;
        float swirl = fbm(vec3(atan(dq.y, dq.x) * 2.0 - time * 0.6, e * 1.5, 1.0));
        float disk = smoothstep(1.5, 1.9, e) * smoothstep(5.5, 3.2, e) * (0.45 + 0.75 * swirl);
        float doppler = 0.55 + 0.45 * (q.x / max(length(q), 1e-4));
        vec3 hot = mix(vec3(1.0, 0.45, 0.15), vec3(1.0, 0.95, 0.85), smoothstep(4.0, 1.6, e));
        bool front = q.y < 0.0 || length(q) > Rs * 1.05;
        if (front) col += hot * disk * doppler * 1.6;
        // The far side of the disk, lensed up over the top of the hole.
        float arc = exp(-pow((length(q) - Rs * 1.55) / (Rs * 0.22), 2.0)) * smoothstep(0.0, Rs, q.y);
        col += hot * arc * 0.9;
        // Photon ring and the event horizon.
        col += vec3(1.0, 0.85, 0.65) * exp(-pow((ang - Rs * 1.12) / (Rs * 0.06), 2.0)) * 1.4;
        if (ang < Rs) col = mix(col, vec3(0.0), smoothstep(Rs, Rs * 0.92, ang));
    }
    fragColor = vec4(col, 1.0) * ColorModulator;
}
