#version 330
#extension GL_ARB_separate_shader_objects : require

// Passes 2 and 3 of the brick look: blur the crease shadow along one direction, but not across depth edges.

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 DepthSize;
};

layout(std140) uniform AoBlurConfig {
    vec2 BlurDir;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const float NEAR = 0.05;

float linearDepth(float d) {
    return NEAR / max(d, 1.0e-7);
}

void main() {
    vec4 centre = texture(InSampler, texCoord);
    float z0 = linearDepth(texture(DepthSampler, texCoord).r);
    float step = OutSize.y / 720.0 * 1.6;
    float sum = 0.0;
    float weight = 0.0;
    for (int i = -5; i <= 5; i++) {
        vec2 uv = texCoord + BlurDir * float(i) * step / OutSize;
        float zi = linearDepth(texture(DepthSampler, uv).r);
        float w = exp(-float(i * i) / 12.0) * exp(-abs(zi - z0) / (0.03 * z0 + 0.02));
        sum += texture(InSampler, uv).r * w;
        weight += w;
    }
    fragColor = vec4(sum / max(weight, 1.0e-4), centre.g, 0.0, 1.0);
}
