attribute vec2 aPos;
varying vec2 vUv;
uniform float uFlipY;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos.x, aPos.y * uFlipY, 0.0, 1.0);
}
