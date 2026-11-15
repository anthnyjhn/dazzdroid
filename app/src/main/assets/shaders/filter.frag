// DazzDroid film engine: one pass that emulates optics, film chemistry and tape/sensor quirks.
// The host prepends "#define SRC_OES" + the external-image extension when sampling a camera/decoder stream.
precision highp float;

#ifdef SRC_OES
uniform samplerExternalOES uTex;
#else
uniform sampler2D uTex;
#endif
uniform sampler2D uOverlay;   // premultiplied RGBA overlay (date stamp)
uniform sampler2D uDouble;    // first frame of a double exposure

varying vec2 vUv;

// geometry
uniform mat4 uTexMatrix;
uniform mat2 uOrient;
uniform vec4 uCrop;
uniform vec2 uRes;
uniform float uTime;
uniform float uIntensity;
uniform float uOverlayOn;
uniform float uDoubleOn;
uniform int uLens;            // 0 none, 1 fisheye, 2 prism, 3 starburst
uniform float uLensAmt;

// color science
uniform float uExposure;
uniform float uContrast;
uniform float uSaturation;
uniform float uTemp;
uniform float uTint;
uniform float uScurve;
uniform vec3 uGamma;
uniform vec3 uBlack;
uniform vec3 uWhite;
uniform mat3 uMix;
uniform vec3 uShadowTint;
uniform vec3 uHighTint;
uniform float uMono;
uniform vec3 uMonoMix;
uniform vec3 uMonoTone;

// optics / medium
uniform float uGrain;
uniform float uGrainSize;
uniform float uVignette;
uniform float uVignetteSoft;
uniform float uHalation;
uniform float uBloom;
uniform float uSoft;
uniform float uSharpen;
uniform float uCa;
uniform float uNoise;
uniform float uLeak;
uniform vec3 uLeakColor;
uniform float uLeakSeed;
uniform float uVhs;
uniform float uLowRes;
uniform float uScan;
uniform float uJitter;
uniform float uDust;
uniform float uFlicker;
uniform float uGate;
uniform float uFlash;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}
float vnoise(vec2 x) {
    vec2 i = floor(x);
    vec2 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

vec2 toSrc(vec2 p) {
    vec2 q = uCrop.zw + p * uCrop.xy;
    q = uOrient * (q - 0.5) + 0.5;
    return (uTexMatrix * vec4(q, 0.0, 1.0)).xy;
}
vec3 src(vec2 p) { return texture2D(uTex, toSrc(p)).rgb; }

vec2 mirrorUv(vec2 v) { return 1.0 - abs(1.0 - mod(v, 2.0)); }

vec2 shortUnit() { return vec2(min(uRes.x, uRes.y)) / uRes; }

vec3 rgb2yiq(vec3 c) {
    return vec3(dot(c, vec3(0.299, 0.587, 0.114)),
                dot(c, vec3(0.596, -0.274, -0.322)),
                dot(c, vec3(0.211, -0.523, 0.312)));
}
vec3 yiq2rgb(vec3 y) {
    return vec3(y.x + 0.956 * y.y + 0.621 * y.z,
                y.x - 0.272 * y.y - 0.647 * y.z,
                y.x - 1.106 * y.y + 1.703 * y.z);
}

// Tape: soft horizontal luma, smeared + delayed chroma.
vec3 vhsSample(vec2 p) {
    float px = 1.0 / uLowRes;
    float Y = 0.0;
    for (int i = -2; i <= 2; i++) {
        Y += rgb2yiq(src(p + vec2(float(i) * px * 0.5, 0.0))).x;
    }
    Y /= 5.0;
    vec2 iq = vec2(0.0);
    for (int i = 0; i < 6; i++) {
        iq += rgb2yiq(src(p + vec2((float(i) - 0.5) * px * 1.6, 0.0))).yz;
    }
    iq /= 6.0;
    vec3 c = yiq2rgb(vec3(Y, iq * 1.15));
    // RGB fringing
    float r = src(p + vec2(px * 1.2, 0.0)).r;
    c.r = mix(c.r, r, 0.35 * uCa);
    return c;
}

vec3 caSample(vec2 p) {
    vec2 d = (p - 0.5) * uCa * 0.012;
    return vec3(src(p + d).r, src(p).g, src(p - d).b);
}

vec3 ringBlur(vec2 p, float r) {
    vec2 u = shortUnit() * r;
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        acc += src(p + vec2(cos(a), sin(a)) * u);
    }
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398 + 0.392699;
        acc += src(p + vec2(cos(a), sin(a)) * u * 2.3);
    }
    return acc / 16.0;
}

vec2 fisheye(vec2 p, out float mask) {
    vec2 a = uRes / min(uRes.x, uRes.y);
    vec2 d = (p - 0.5) * 2.0 * a;
    float r = length(d);
    float fov = mix(1.4, 2.7, uLensAmt);
    float rc = min(r, 1.0);
    float sr = tan(rc * fov * 0.5) / tan(fov * 0.5);
    mask = 1.0 - smoothstep(0.985, 1.0, r);
    if (r > 0.0001) d *= sr / r;
    return d / (2.0 * a) + 0.5;
}

float bright(vec2 q) { return max(luma(src(q)) - 0.8, 0.0) * 5.0; }

vec3 grade(vec3 c) {
    c *= exp2(uExposure);
    c *= vec3(1.0 + uTemp * 0.22, 1.0 + uTemp * 0.02 - uTint * 0.14, 1.0 - uTemp * 0.24);
    c = uMix * c;
    c = max(c, 0.0);
    c = (c - 0.5) * uContrast + 0.5;
    vec3 sc = clamp(c, 0.0, 1.0);
    sc = sc * sc * (3.0 - 2.0 * sc);
    c = mix(c, sc, uScurve);
    c = clamp(c, 0.0, 1.0);
    c = pow(c, 1.0 / uGamma);
    float m = dot(c, uMonoMix);
    c = mix(c, vec3(m), uMono);
    float l = luma(c);
    c = mix(vec3(l), c, uSaturation);
    c += uShadowTint * (1.0 - smoothstep(0.0, 0.55, l));
    c += uHighTint * smoothstep(0.45, 1.0, l);
    c *= mix(vec3(1.0), uMonoTone, uMono);
    c = uBlack + clamp(c, 0.0, 1.0) * (uWhite - uBlack);
    return c;
}

void main() {
    vec2 p = vUv;
    float fr = floor(uTime * 18.0);

    // gate weave / projector jitter
    if (uJitter > 0.0) {
        p += (vec2(hash11(fr * 1.7 + 3.1), hash11(fr * 2.3 + 7.7)) - 0.5) * uJitter * vec2(0.003, 0.006);
    }
    // tape wobble, tracking band, head-switching noise
    if (uVhs > 0.0) {
        float line = floor(p.y * 240.0);
        float wob = sin(p.y * 40.0 + uTime * 3.0) * 0.0007 + sin(p.y * 7.0 - uTime * 1.3) * 0.0010;
        float band = fract(uTime * 0.07);
        float bandAmt = 1.0 - smoothstep(0.0, 0.02, abs(p.y - band));
        wob += bandAmt * (hash12(vec2(line, fr)) - 0.5) * 0.02;
        float bottom = 1.0 - smoothstep(0.0, 0.03, p.y);
        wob += bottom * hash12(vec2(line, fr + 3.0)) * 0.025;
        p.x += wob * uVhs;
    }

    float mask = 1.0;
    if (uLens == 1) p = fisheye(p, mask);

    vec3 orig = src(p);
    vec3 c = orig;
    if (uVhs > 0.0) c = vhsSample(p);
    else if (uCa > 0.0) c = caSample(p);

    if (uLens == 2) {
        vec2 sh = vec2(0.2 * uLensAmt + 0.06, 0.015);
        vec3 g1 = vec3(src(mirrorUv(p + sh)).r, src(mirrorUv(p + sh * 1.04)).g, src(mirrorUv(p + sh * 1.08)).b);
        vec3 g2 = vec3(src(mirrorUv(p - sh * 1.08)).r, src(mirrorUv(p - sh * 1.04)).g, src(mirrorUv(p - sh)).b);
        vec3 ghost = (g1 + g2) * 0.5;
        c = mix(c, max(c, ghost), 0.55) * 0.85 + ghost * 0.15;
    }

    if (uSoft + uHalation + uBloom + uFlash > 0.0) {
        vec3 b = ringBlur(p, 0.006);
        float bl = luma(b);
        vec3 hi = b * smoothstep(0.55, 1.0, bl);
        c = mix(c, max(c, b), uSoft * 0.5);
        c = mix(c, b, uSoft * 0.3);
        c += hi * (uBloom + uFlash * 0.3) * 0.6;
        c += hi * vec3(1.0, 0.32, 0.12) * uHalation * 0.9;
    }

    if (uSharpen > 0.0) {
        vec2 px = max(1.0, min(uRes.x, uRes.y) / 1080.0) / uRes;
        vec3 n = src(p + vec2(px.x, 0.0)) + src(p - vec2(px.x, 0.0)) + src(p + vec2(0.0, px.y)) + src(p - vec2(0.0, px.y));
        c += (c - n * 0.25) * uSharpen * 1.2;
    }

    if (uLens == 3) {
        vec2 u = shortUnit();
        float star = 0.0;
        for (int i = 1; i <= 12; i++) {
            float t = float(i) / 12.0;
            float w = (1.0 - t) * (1.0 - t);
            vec2 o = t * (0.08 + 0.14 * uLensAmt) * u;
            star += w * (bright(p + o) + bright(p - o) + bright(p + vec2(o.x, -o.y)) + bright(p + vec2(-o.x, o.y)));
        }
        c += vec3(1.0, 0.97, 0.92) * star * 0.22;
    }

    if (uFlash > 0.0) {
        float r = length((p - 0.5) * uRes / min(uRes.x, uRes.y));
        c *= 1.0 + uFlash * (0.55 * (1.0 - smoothstep(0.0, 0.75, r)) + 0.12);
    }

    vec3 g = grade(c);

    if (uLeak > 0.0) {
        float s = uLeakSeed;
        float side = step(0.5, hash11(s * 13.1 + 1.0));
        float x = clamp(mix(vUv.x, 1.0 - vUv.x, side), 0.0, 1.0);
        float cy = 0.25 + 0.5 * hash11(s * 7.7 + 2.0) + 0.05 * sin(uTime * 0.5);
        float blob = exp(-pow((vUv.y - cy) * 2.4, 2.0));
        float leak = pow(1.0 - x, 3.0) * (0.35 + 1.3 * blob) + 0.6 * pow(1.0 - x, 10.0);
        vec3 lc = clamp(uLeakColor * leak * uLeak, 0.0, 1.0);
        g = 1.0 - (1.0 - g) * (1.0 - lc);
    }

    if (uVignette > 0.0) {
        float vd = length(vUv - 0.5) * 1.4142;
        g *= 1.0 - uVignette * smoothstep(0.3, 0.3 + 0.45 * uVignetteSoft + 0.35, vd);
    }

    if (uGrain > 0.0) {
        float sc = max(min(uRes.x, uRes.y) / 1080.0, 0.5) * uGrainSize;
        vec2 gp = gl_FragCoord.xy / sc + vec2(hash11(fr + 0.3), hash11(fr + 9.1)) * 500.0;
        float n = vnoise(gp) * 0.65 + vnoise(gp * 2.3 + 17.0) * 0.35 - 0.5;
        float l = luma(g);
        float w = max(1.0 - pow(abs(l - 0.45) * 1.8, 2.0) * 0.7, 0.15);
        g += n * uGrain * 0.22 * w;
    }

    if (uNoise > 0.0) {
        vec2 q = floor(gl_FragCoord.xy / max(1.0, min(uRes.x, uRes.y) / 1080.0)) + fr * 7.0;
        vec3 nz = vec3(hash12(q), hash12(q + 31.7), hash12(q + 71.3)) - 0.5;
        float l = luma(g);
        g += (nz * 0.6 + nz.r * 0.4) * uNoise * 0.12 * (1.0 - l * 0.8);
    }

    if (uScan > 0.0) {
        float sl = 0.5 + 0.5 * sin(vUv.y * 240.0 * 6.2831853);
        g *= 1.0 - uScan * 0.28 * sl;
    }

    if (uDust > 0.0) {
        for (int i = 0; i < 2; i++) {
            float fi = float(i);
            float on = step(hash11(fr * 1.31 + fi * 4.7), 0.35 * uDust);
            float xl = hash11(fr * 0.73 + fi * 11.3) + 0.003 * sin(vUv.y * 9.0 + fr);
            float ln = 1.0 - smoothstep(0.0, 0.0012 * (1.0 + fi), abs(vUv.x - xl));
            g = mix(g, vec3(0.85, 0.82, 0.75), ln * on * 0.55);
        }
        vec2 cells = vec2(36.0, 36.0 * uRes.y / uRes.x);
        vec2 cid = floor(vUv * cells);
        vec2 cf = fract(vUv * cells) - 0.5;
        if (hash12(cid + fr * 3.17) < 0.012 * uDust) {
            vec2 off = vec2(hash12(cid + 1.3), hash12(cid + 2.7)) - 0.5;
            float rad = 0.06 + 0.18 * hash12(cid + 5.1);
            float spot = 1.0 - smoothstep(rad * 0.6, rad, length(cf - off * 0.5));
            vec3 speck = hash12(cid + 8.8) > 0.3 ? g * 0.15 : vec3(0.95);
            g = mix(g, speck, spot * 0.85);
        }
    }

    if (uFlicker > 0.0) {
        g *= 1.0 + (hash11(fr * 0.91 + 5.0) - 0.5) * 0.14 * uFlicker;
    }

    if (uGate > 0.0) {
        vec2 a = uRes / min(uRes.x, uRes.y);
        vec2 q = (vUv - 0.5) * a;
        float rad = 0.08;
        vec2 d = abs(q) - (0.5 * a - vec2(0.025)) + rad;
        float sd = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - rad;
        g *= mix(1.0, 1.0 - smoothstep(-0.01, 0.004, sd), uGate);
    }

    vec3 outc = mix(orig, g, uIntensity) * mask;

    if (uDoubleOn > 0.0) {
        vec3 d = texture2D(uDouble, vec2(vUv.x, 1.0 - vUv.y)).rgb;
        outc = 1.0 - (1.0 - outc * 0.85) * (1.0 - d * 0.85 * uDoubleOn);
    }
    if (uOverlayOn > 0.0) {
        vec4 o = texture2D(uOverlay, vec2(vUv.x, 1.0 - vUv.y));
        outc = outc * (1.0 - o.a) + o.rgb;
    }
    gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
}
