#version 330
#extension GL_ARB_separate_shader_objects : require

// Pass 1 of the brick look: how much each pixel sits in a crease (r) and on an outer corner (g), from depth only.
// Samples are turned by a per-pixel noise so there are no stepped copies; the next passes blur r smooth.

uniform sampler2D DepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 DepthSize;
};

layout(std140) uniform AoConfig {
    float AoRadius;
    float AoRange;
    float EdgeStrength;
    float Pad;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const float NEAR = 0.05;
const float TAN_HALF_FOV = 0.55;

float linearDepth(float d) {
    return NEAR / max(d, 1.0e-7);
}

float depthAt(vec2 uv) {
    return linearDepth(texture(DepthSampler, uv).r);
}

float noise(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
    float raw = texture(DepthSampler, texCoord).r;
    if (raw <= 1.0e-6) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float z = linearDepth(raw);
    float scale = OutSize.y / 720.0;
    float footprint = z * 2.0 * TAN_HALF_FOV / OutSize.y;
    float n = noise(gl_FragCoord.xy);

    // creases: opposite sample pairs; flat or sloped surfaces cancel out, a crease leaves both sides nearer
    float radius = clamp(AoRadius / footprint, 2.0 * scale, 36.0 * scale);
    float occ = 0.0;
    const int PAIRS = 10;
    for (int i = 0; i < PAIRS; i++) {
        float a = (float(i) + n) * (3.14159265 / float(PAIRS));
        float r = radius * (0.15 + 0.85 * fract(n * 7.31 + float(i) * 0.618));
        vec2 off = vec2(cos(a), sin(a)) * r / OutSize;
        float dp = z - depthAt(texCoord + off);
        float dn = z - depthAt(texCoord - off);
        float crease = 0.5 * (dp + dn);
        float range = 1.0 - smoothstep(AoRange * 0.4, AoRange, max(dp, dn));
        occ += smoothstep(0.01, 0.09, crease) * range;
    }
    occ /= float(PAIRS);

    // outer corners: the same test at a tiny radius, the other way round; thin things (grass) are left out
    float er = 1.5 * scale;
    float bump = 0.0;
    for (int i = 0; i < 4; i++) {
        float a = float(i) * 0.7854;
        vec2 off = vec2(cos(a), sin(a)) * er / OutSize;
        float dp = depthAt(texCoord + off) - z;
        float dn = depthAt(texCoord - off) - z;
        float ridge = 0.5 * (dp + dn) / (footprint * er);
        float range = 1.0 - smoothstep(0.04, 0.12, max(dp, dn));
        bump = max(bump, smoothstep(0.15, 0.6, ridge) * range);
    }
    float fade = 1.0 - smoothstep(48.0, 120.0, z);
    fragColor = vec4(occ * fade, bump * EdgeStrength * fade, 0.0, 1.0);
}
