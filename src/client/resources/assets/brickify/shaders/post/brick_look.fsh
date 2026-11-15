#version 330
#extension GL_ARB_separate_shader_objects : require

// Last pass of the brick look: soft crease shadow, thin bright edges on outer corners, a soft plastic gloss,
// livelier warm colours and a light vignette.

uniform sampler2D InSampler;
uniform sampler2D AoSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 AoSize;
    vec2 DepthSize;
};

layout(std140) uniform LookConfig {
    float Strength;
    float AoStrength;
    float Sheen;
    float Saturation;
    float Contrast;
    float Warmth;
    float Vignette;
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

vec3 viewPos(vec2 uv, float z) {
    float aspect = OutSize.x / OutSize.y;
    vec2 ndc = uv * 2.0 - 1.0;
    return vec3(ndc.x * aspect * TAN_HALF_FOV * z, ndc.y * TAN_HALF_FOV * z, -z);
}

void main() {
    vec3 color = texture(InSampler, texCoord).rgb;
    vec4 ao = texture(AoSampler, texCoord);
    float raw = texture(DepthSampler, texCoord).r;
    bool sky = raw <= 1.0e-6;
    float z = linearDepth(raw);
    float fade = sky ? 0.0 : 1.0 - smoothstep(48.0, 120.0, z);

    // soft plastic gloss from the surface direction (light from the upper left behind the camera)
    float gloss = 0.0;
    if (!sky) {
        vec2 px = 1.0 / OutSize;
        float zr = depthAt(texCoord + vec2(px.x, 0.0));
        float zu = depthAt(texCoord + vec2(0.0, px.y));
        if (abs(zr - z) < 0.02 * z && abs(zu - z) < 0.02 * z) {
            vec3 p = viewPos(texCoord, z);
            vec3 n = normalize(cross(viewPos(texCoord + vec2(px.x, 0.0), zr) - p, viewPos(texCoord + vec2(0.0, px.y), zu) - p));
            vec3 v = normalize(-p);
            if (dot(n, v) < 0.0) {
                n = -n;
            }
            vec3 l = normalize(vec3(-0.4, 0.8, 0.45));
            vec3 h = normalize(l + v);
            gloss = pow(max(dot(n, h), 0.0), 20.0) * 0.3 * Sheen * fade;
        }
    }

    float lum = dot(color, vec3(0.299, 0.587, 0.114));
    vec3 graded = color * (1.0 - AoStrength * ao.r);
    graded += vec3(1.0, 0.98, 0.94) * gloss * (0.4 + 0.6 * lum);
    graded = mix(graded, graded * 1.2 + 0.04, ao.g);
    float grey = dot(graded, vec3(0.299, 0.587, 0.114));
    graded = mix(vec3(grey), graded, Saturation);
    graded = (graded - 0.5) * Contrast + 0.5;
    graded *= vec3(1.0 + Warmth, 1.0 + Warmth * 0.35, 1.0 - Warmth);
    vec2 c = texCoord - 0.5;
    graded *= 1.0 - Vignette * pow(clamp(length(c) * 1.35, 0.0, 1.0), 2.4);
    fragColor = vec4(mix(color, clamp(graded, 0.0, 1.0), Strength), 1.0);
}
