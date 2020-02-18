#version 320 es
#extension GL_OES_EGL_image_external_essl3 : require

precision highp float;
precision mediump int;
precision lowp sampler2D;

in vec2 v_TexCoord;

layout(binding=0) uniform samplerExternalOES ColorTexture;
layout(binding=1) uniform lowp usampler2D DepthTexture;

uniform float u_vizMode;
uniform float u_DepthThresh;

uniform vec2 u_ScreenResolution;

// For scaling/cropping depth data to screen size.
uniform float u_Depth_y_offset;
uniform float u_Depth_x_scale_factor;
uniform float u_Depth_y_scale_factor;

out vec4 FragColor;

// for occlusion
const float zNear = 0.001;
const float zFar = 1.0;

// Maximum range of the TOF depth sensor in mm
const float MAX_RANGE_MM = 11000.0f;
const float kDepthOffsets = 8192.0; // 1 << 13      // to decode depth data.

float when_lt(float x, float y) {
    return max(sign(y - x), 0.0f);
}

float when_ge(float x, float y) {
    return 1.0f - when_lt(x, y);
}


float when_eq(float x, float y) {
    return 1.0f - abs(sign(x - y));
}

float when_neq(float x, float y) {
    return abs(sign(x - y));
}

vec4 when_neq(vec4 x, vec4 y) {
    return abs(sign(x - y));
}

float when_gt(float x, float y) {
    return max(sign(x - y), 0.0f);
}

vec4 when_gt(vec4 x, vec4 y) {
    return max(sign(x - y), 0.0f);
}


float when_le(float x, float y) {
    return 1.0f - when_gt(x, y);
}


// Extracts confidence from depth + confidence value. This is the same as doing
// 'depth >> 13' but since shaders don't support bitwise operations, it
// has to be done this way.
float GetUnpackedConfidence(float depth_mm) {
    return floor(depth_mm / kDepthOffsets);
}

// Extracts depth from confidence + depth value. This is the same as doing
// 'depth & 0x1FFF' but since shaders don't support bitwise operations, it
// has to be done this way.
float GetUnpackedDepth(float depth_mm) {
    return depth_mm - (GetUnpackedConfidence(depth_mm) * kDepthOffsets);
}

float GetUnpackedDepthXY(ivec2 depthPoint){
    //uint raw_depth_uint16 = texture(DepthTexture, v_TexCoord).r;
    uint raw_depth_uint16 = texelFetch(DepthTexture, depthPoint, 0).r;
    float raw_depth = float(raw_depth_uint16);
    float unpacked_depth = GetUnpackedDepth(raw_depth);
    unpacked_depth = unpacked_depth * when_lt(unpacked_depth, MAX_RANGE_MM);
    return unpacked_depth;
}

ivec2 screenCoord2DepthCoord(vec2 screenPoint){
    ivec2 result;
    result.x = int(screenPoint.x * u_Depth_x_scale_factor);
    result.y = int((screenPoint.y * u_Depth_y_scale_factor) + u_Depth_y_offset);
    return result;
}


float Screen2Depth(vec2 screenPoint){
    ivec2 depthPoint = screenCoord2DepthCoord(screenPoint);
    return GetUnpackedDepthXY(depthPoint);
}


// result suitable for assigning to gl_FragDepth
float depthToNonLinear(float linearDepth)
{
    float nonLinearDepth = (zFar + zNear - 2.0 * zNear * zFar / linearDepth) / (zFar - zNear);
    nonLinearDepth = (nonLinearDepth + 1.0) / 2.0;
    return nonLinearDepth;
}


// result suitable for visualizing via Chromadepth algorithm.
vec4 depthViz(float linearDepth, vec4 colors, float thresh, float showColor)
{
   float depth = linearDepth;
   depth = depth * thresh;
   depth = depth * 100.0;

    /* ChromaDepth GLSL Shader v1.0
 * Copyright (c) 2009 Nirav Patel <http://eclecti.cc>
 * License: WTFPL
 *
 * This shader generates images that look remarkably 3d when viewed with
 * ChromaDepth glasses.  The technology was developed by American Paper Optics
 * <http://www.chromatek.com/>, from which you can get more information on
 * physics of it, palettes to use, and where to order the glasses.
 */
    vec4 rgb;
    /* these formulas are based on code from American Paper Optics at:
       http://www.chromatek.com/Image_Design/Color_Lookup_Functions/color_lookup_functions.shtml */
    float depth2 = depth*depth;

    // depth greater than 0.5f
    float g1 = (1.6f*depth2+1.2f*depth)  * when_gt(0.5f, depth);

    // depth less than or equal to 0.5f
    float g2 = (3.2f*depth2-6.8f*depth+3.6f) * when_le(0.5f, depth);
    rgb.b = (depth2*-4.8f+9.2f*depth-3.4f) * when_le(0.5f, depth);

    rgb.g = max(g1, g2);

    depth = depth/0.9f;
    depth2 = depth2/0.81f;
    rgb.r = -2.14f*depth2*depth2 -1.07f*depth2*depth + 0.133f*depth2 +0.0667f*depth +1.0f;

    // depth holes / zero depth set to black
    rgb.r = rgb.r * when_neq(linearDepth, 0.0f);

    // use rgb camera colors for depth holes
    vec4 rgb_out = rgb;
    rgb_out.r += colors.r * when_eq(linearDepth, 0.0f) * when_eq(showColor, 1.0f);
    rgb_out.g += colors.g * when_eq(linearDepth, 0.0f) * when_eq(showColor, 1.0f);
    rgb_out.b += colors.b * when_eq(linearDepth, 0.0f) * when_eq(showColor, 1.0f);

    return rgb_out;
}

vec3 GetNormalFromDepth(ivec2 depthPoint)
{
    int radius = 1;
    vec3 t = vec3(depthPoint.x, depthPoint.y-radius, GetUnpackedDepthXY(ivec2(depthPoint.x, depthPoint.y-radius)));
    vec3 l = vec3(depthPoint.x-radius, depthPoint.y, GetUnpackedDepthXY(ivec2(depthPoint.x-radius, depthPoint.y)));
    vec3 c = vec3(depthPoint.x, depthPoint.y, GetUnpackedDepthXY(ivec2(depthPoint.x, depthPoint.y)));
    vec3 d = cross(l-c, t-c);
    vec3 n = normalize(d);
    return n;
}


// give normals a 'true 3D' shaded look with light coming from a certain direction
float shadedNormal(vec3 normal){
    highp vec3 lightDirection=vec3(1.0f,-2.0f,3.0f);
    highp float shading=0.5f+0.5f*dot(normal,normalize(lightDirection));
    return shading;
}

vec4 normalViz(float shading, float linearDepth, vec4 colors, float showColor){
    shading *= when_neq(linearDepth, 0.0f);
    vec4 rgb = vec4(shading, shading, shading, 1.0f);
    rgb += colors * when_eq(linearDepth, 0.0f) * when_eq(showColor, 1.0f);
    return rgb;
}


void main() {
    vec4 colors = texture(ColorTexture, v_TexCoord.xy);

    vec2 screenPoint = vec2(gl_FragCoord.x, u_ScreenResolution.y - gl_FragCoord.y) - 0.5f;
    ivec2 depthPoint = screenCoord2DepthCoord(screenPoint);

    uint raw_depth_uint16 = texelFetch(DepthTexture, depthPoint, 0).r;
    //uint raw_depth_uint16 = texture(DepthTexture, v_TexCoord.xy).r;

    float raw_depth = float(raw_depth_uint16);
    float depthConfidence = GetUnpackedConfidence(raw_depth);
    float depthPercentage = depthConfidence == 0.0f ? 1.0f : (depthConfidence - 1.0f) / 7.0f;
    float unpacked_depth = GetUnpackedDepth(raw_depth);
    unpacked_depth = unpacked_depth * when_lt(unpacked_depth, MAX_RANGE_MM);

    // convert raw 16 bit depth from TOF to float value between zero and 1.
    float z_linear = unpacked_depth / MAX_RANGE_MM;
    z_linear = clamp(z_linear, 0.0f, 1.0f); // z_linear depth should go no higher than 1.0

    // ********************* Visualization modes *****************************************************
    //  *************************************************************************************************
    vec3 normal = GetNormalFromDepth(depthPoint);
    float shading = shadedNormal(normal);
    float mode = -1.0f;
    mode++; if(u_vizMode ==  mode) FragColor = depthViz(z_linear, colors, u_DepthThresh, 1.0f);
    mode++; if(u_vizMode ==  mode) FragColor = depthViz(z_linear, colors, u_DepthThresh, 0.0f);
    mode++; if(u_vizMode ==  mode) FragColor = normalViz(shading, z_linear, colors, 0.0f);
    mode++; if(u_vizMode ==  mode) FragColor = normalViz(shading, z_linear, colors, 1.0f);
    mode++; if(u_vizMode ==  mode) FragColor = vec4(depthPercentage, depthPercentage, depthPercentage, 1.0f);
    mode++; if(u_vizMode ==  mode) FragColor = vec4(normal.r, normal.g, normal.b, 1.0f);
    mode++; if(u_vizMode >= mode) FragColor = vec4(colors.rgb - (colors.rgb * 0.5f * when_eq(z_linear, 0.0f)), 1.0f);      // darken rgb image pixels when depth is zero, to visualize lack of depth data

}


