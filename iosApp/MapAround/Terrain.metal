#include <metal_stdlib>
using namespace metal;

// Phase 0 spike (c): the shared terrain mesh (TileMesh: position, normal as (-dh/dx, -dh/dy, 1),
// uv), lit and fogged like the Android 3D view's terrain shader.

struct VertexIn {
    float3 pos [[attribute(0)]];
    float3 normal [[attribute(1)]];
    float2 uv [[attribute(2)]];
};

struct Uniforms {
    float4x4 mvp;
    float4 eye;      // xyz
    float4 light;    // xyz, towards the light
    float4 sky;      // rgb
    float4 params;   // exaggeration, fog start, fog end, has texture
    float4 heights;  // min, max (metres)
};

struct VertexOut {
    float4 position [[position]];
    float2 uv;
    float3 normal;
    float dist;
    float height;
};

vertex VertexOut terrainVertex(VertexIn in [[stage_in]], constant Uniforms &u [[buffer(1)]]) {
    float e = u.params.x;
    float3 p = float3(in.pos.xy, in.pos.z * e);
    VertexOut out;
    out.position = u.mvp * float4(p, 1.0);
    out.uv = in.uv;
    out.normal = float3(in.normal.xy * e, in.normal.z);
    out.dist = distance(p, u.eye.xyz);
    out.height = in.pos.z;
    return out;
}

fragment float4 terrainFragment(VertexOut in [[stage_in]], constant Uniforms &u [[buffer(1)]],
                                texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {
    float light = 0.72 + 0.38 * dot(normalize(in.normal), normalize(u.light.xyz));
    float3 c;
    if (u.params.w > 0.5) {
        c = tex.sample(s, in.uv).rgb;
    } else {
        // Until the map picture is ready: green valleys to pale summits.
        float t = clamp((in.height - u.heights.x) / max(1.0, u.heights.y - u.heights.x), 0.0, 1.0);
        c = mix(float3(0.50, 0.66, 0.38), float3(0.88, 0.85, 0.78), t);
    }
    c *= clamp(light, 0.45, 1.1);
    float fog = clamp((in.dist - u.params.y) / (u.params.z - u.params.y), 0.0, 0.85);
    return float4(mix(c, u.sky.rgb, fog), 1.0);
}
