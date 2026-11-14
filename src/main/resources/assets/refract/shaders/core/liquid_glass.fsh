#version 330

// Vanta liquid glass. Sampler0 is a copy of the rendered world. Per vertex:
//   Color.rg = position inside the panel (0..1), Color.b = corner radius / half the short side, Color.a = opacity
//   UV0.x    = how far the rim bends the image (px), UV0.y = style (0 liquid, 1 frosted)

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

float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 tex = vec2(textureSize(Sampler0, 0));
    vec2 uv = gl_FragCoord.xy / tex;
    vec2 local = vertexColor.rg;
    // Panel size in pixels from how fast the local coordinate changes per pixel.
    vec2 size = vec2(1.0 / max(length(vec2(dFdx(local.x), dFdy(local.x))), 1e-5),
                     1.0 / max(length(vec2(dFdx(local.y), dFdy(local.y))), 1e-5));
    vec2 hs = size * 0.5;
    vec2 p = (local - 0.5) * size;                 // px from the centre, y pointing down
    float r = clamp(vertexColor.b, 0.0, 1.0) * min(hs.x, hs.y);
    float d = sdRoundBox(p, hs, r);                // < 0 inside
    float mask = clamp(0.5 - d, 0.0, 1.0);         // one pixel of anti-aliasing on the edge
    if (mask <= 0.0) discard;

    vec2 e = vec2(0.75, 0.0);
    vec2 n = vec2(sdRoundBox(p + e.xy, hs, r) - sdRoundBox(p - e.xy, hs, r),
                  sdRoundBox(p + e.yx, hs, r) - sdRoundBox(p - e.yx, hs, r));
    n = n / max(length(n), 1e-4);                  // outward edge normal

    float bend = texCoord0.x;
    bool frosted = texCoord0.y > 0.5;
    float band = max(bend * 2.2, 9.0);
    float t = clamp(1.0 + d / band, 0.0, 1.0);    // 0 inside, 1 at the rim
    float lens = t * t * t * (frosted ? 0.4 : 1.0);

    // Lens: magnify the middle, then pull the image inward hard near the rim.
    float radial = clamp(length(p / hs), 0.0, 1.0);
    vec2 src = p * mix(frosted ? 1.0 : 0.86, 1.0, radial * radial) - n * bend * lens;
    vec2 off = src - p;
    vec2 offUV = vec2(off.x, -off.y) / tex;

    vec3 col;
    if (frosted) {
        // Frosted: a 16-tap disk blur around the (gently) refracted point.
        col = vec3(0.0);
        float ga = 2.39996;
        for (int i = 0; i < 16; i++) {
            float fi = float(i) + 0.5;
            float rr = sqrt(fi / 16.0) * 7.0;
            vec2 o = vec2(cos(fi * ga), sin(fi * ga)) * rr / tex;
            col += texture(Sampler0, uv + offUV + o).rgb;
        }
        col /= 16.0;
    } else {
        // Chromatic dispersion: red bends a little more than green, blue a little less.
        float disp = 0.03 + 0.14 * lens;
        col.r = texture(Sampler0, uv + offUV * (1.0 + disp)).r;
        col.g = texture(Sampler0, uv + offUV).g;
        col.b = texture(Sampler0, uv + offUV * (1.0 - disp)).b;
    }

    // Coloured reflection: near the rim the glass mirrors what is just outside it.
    float rimZone = smoothstep(-band * 0.7, 0.0, d);
    vec3 refl = texture(Sampler0, uv + vec2(n.x, -n.y) * (band * 1.2 + 6.0) / tex).rgb;
    col = mix(col, refl * 1.15, 0.3 * rimZone);

    // Light: a bright specular catch where the rim faces the light (top-left), a softer one opposite, a top sheen,
    // and a touch of shading just inside the rim for thickness.
    float rim = 1.0 - smoothstep(0.0, 2.4, -d);
    vec2 L = normalize(vec2(-0.7, -1.0));
    float spec = pow(max(dot(n, L), 0.0), 2.2) * rim * 0.95 + pow(max(dot(n, -L), 0.0), 3.0) * rim * 0.4;
    float sheen = (1.0 - smoothstep(-hs.y, -hs.y * 0.2, p.y)) * 0.06;
    float inner = smoothstep(-band, -band * 0.35, d) * (1.0 - rim) * 0.12;
    col = col * (1.0 - inner) + vec3(spec + sheen);

    fragColor = vec4(col, mask * vertexColor.a) * ColorModulator;
}
