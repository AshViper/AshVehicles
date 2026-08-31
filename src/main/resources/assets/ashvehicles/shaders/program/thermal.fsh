#version 150

// Gun camera sensor image. See com.ashvehicles.client.ThermalView for why this exists and
// how each term was chosen -- ASCII only in here, because a GLSL comment holding anything
// else is rejected outright by some drivers. No arrays and no loops either: the ring below
// is written out by hand so the whole thing stays inside what every driver agrees on.

uniform sampler2D DiffuseSampler;

uniform vec2 InSize;
uniform float Time;
uniform float Polarity;
uniform float Gain;
uniform float Bias;
uniform float Bloom;
uniform float Grain;
uniform float Vignette;

in vec2 texCoord;

out vec4 fragColor;

float noise(vec2 seed) {
    return fract(sin(dot(seed, vec2(12.9898, 78.233))) * 43758.5453);
}

// What the sensor reads out of a pixel the eye was shown in colour. Brightness carries most
// of it, then three corrections: foliage and open sky/water read cold whatever their
// brightness, and anything burning reads far hotter than its brightness alone.
float heat(vec3 colour) {
    float lum  = dot(colour, vec3(0.2126, 0.7152, 0.0722));
    float leaf = max(colour.g - max(colour.r, colour.b), 0.0);
    float sky  = max(colour.b - max(colour.r, colour.g), 0.0);
    float fire = max(colour.r - 0.5 * (colour.g + colour.b), 0.0);

    return lum * 0.95 - leaf * 0.85 - sky * 1.25 + fire * 1.90;
}

// How much a neighbour spills onto this pixel. Only what is already near the top of the
// scale contributes, so the bleed belongs to fires and muzzle flashes and nothing else.
float spill(vec2 uv, vec2 offset) {
    return max(heat(texture(DiffuseSampler, uv + offset).rgb) - 0.80, 0.0);
}

// Eight neighbours at one radius. Hot things bleed into their surroundings on a real
// sensor; two of these rings stand in for a separate blur pass.
float ring(vec2 uv, vec2 reach) {
    vec2 diagonal = reach * 0.7;

    return spill(uv, vec2(reach.x, 0.0)) + spill(uv, vec2(-reach.x, 0.0))
         + spill(uv, vec2(0.0, reach.y)) + spill(uv, vec2(0.0, -reach.y))
         + spill(uv, diagonal) + spill(uv, -diagonal)
         + spill(uv, vec2(diagonal.x, -diagonal.y)) + spill(uv, vec2(-diagonal.x, diagonal.y));
}

void main() {
    vec2 texel = 1.0 / InSize;
    float value = heat(texture(DiffuseSampler, texCoord).rgb);

    value += (ring(texCoord, texel * 3.0) + ring(texCoord, texel * 7.0)) * Bloom;
    value = clamp(value, 0.0, 1.0);
    value = clamp((pow(value, 0.85) - 0.5) * Gain + 0.5 + Bias, 0.0, 1.0);

    // Sensor noise: per-pixel grain, plus a fainter per-scanline drift.
    value += (noise(gl_FragCoord.xy + vec2(Time * 311.0, Time * 173.0)) - 0.5) * Grain;
    value += (noise(vec2(floor(gl_FragCoord.y), floor(Time * 97.0))) - 0.5) * Grain * 0.35;

    // Corner shading is part of the signal, so it happens before polarity: the edges read
    // cold either way round rather than turning into a hot frame under black-hot.
    vec2 off = texCoord - 0.5;
    value *= 1.0 - dot(off, off) * Vignette;

    value = mix(1.0 - value, value, Polarity);

    fragColor = vec4(vec3(clamp(value, 0.0, 1.0)), 1.0);
}
