// MapAround's weather map (the web app's data view): NOAA's GFS forecast drawn on the graphics card
// in a MapLibre custom layer: a field as smooth colours (temperature, clouds, rain and snow) and
// the wind as particles drifting with it, leaving fading trails. The pictures come from MapAround's
// pipeline (my-pipeline: weather.py): each colour channel one field over the whole world, 0.5°
// apart, from 180°W and 90°N. The wind's particles are after Vladimir Agafonkin's webgl-wind
// (github.com/mapbox/webgl-wind, ISC), moved in the map's own projection.
//
// MapAroundWeather.set(map, spec | null), with spec:
//   { wt, cp: picture URLs; grid: { width, height, west, north, step };
//     ranges: { u, v, t, cloud, precip, pressure: [lo, hi] }; wind: bool;
//     field: null, or { source: "wt" | "cp", channel: 0-2, range: [lo, hi], sqrt: bool,
//                       stops: [[value, "#rrggbb", alpha], …] } }

/* global maplibregl */
var MapAroundWeather = (function () {
  'use strict';

  const LAYER_ID = 'mr-weather';

  /** Particles: a square of RES × RES. */
  const RES = 96;
  /** How far a particle moves each frame: CSS pixels per m/s of wind. */
  const SPEED_PX = 0.12;
  /** Each frame a few particles start again somewhere else (more in fast wind), so the trails stay even. */
  const DROP = 0.004;
  const DROP_BUMP = 0.01;
  /** What's left of the trails each frame. */
  const FADE = 0.95;

  let map = null;
  let spec = null;
  let hooked = null;

  // ---- colours: a field's scale over its picture's 0-255 ----

  /** The scale as 256 colours (premultiplied later): stops [value, "#rrggbb", alpha] over the field's range. */
  function rampPixels(field) {
    const px = new Uint8Array(256 * 4);
    const [lo, hi] = field.range;
    const stops = field.stops;
    for (let i = 0; i < 256; i++) {
      const f = i / 255;
      const v = field.sqrt ? Math.pow(f * Math.sqrt(hi), 2) : lo + f * (hi - lo);
      let k = 0;
      while (k < stops.length - 2 && v > stops[k + 1][0]) k++;
      const [v0, c0, a0] = stops[k];
      const [v1, c1, a1] = stops[k + 1];
      const t = Math.max(0, Math.min(1, (v - v0) / ((v1 - v0) || 1)));
      const rgb0 = hex(c0), rgb1 = hex(c1);
      for (let j = 0; j < 3; j++) px[i * 4 + j] = Math.round(rgb0[j] + (rgb1[j] - rgb0[j]) * t);
      px[i * 4 + 3] = Math.round((a0 + (a1 - a0) * t) * 255);
    }
    return px;
  }

  function hex(c) {
    return [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
  }

  // ---- shaders ----

  // Mercator's 0-1 square to the grid's texture coordinates (the GFS grid: from 180°W, 90°N).
  const GRID_GLSL = `
    uniform vec4 u_grid; // width, height, west, north (and the step is 360 / width)
    const float PI = 3.141592653589793;
    vec2 gridUv(vec2 merc) {
      float y = PI * (1.0 - 2.0 * merc.y);
      float lat = degrees(atan(0.5 * (exp(y) - exp(-y))));
      float lon = merc.x * 360.0 - 180.0;
      float step = 360.0 / u_grid.x;
      float u = (lon - u_grid.z) / step + 0.5;
      float v = (u_grid.w - lat) / step + 0.5;
      return vec2(fract(u / u_grid.x), v / u_grid.y);
    }`;

  const FIELD_VS = `
    attribute vec2 a_pos;
    uniform mat4 u_matrix;
    uniform float u_world;
    varying vec2 v_merc;
    void main() {
      v_merc = a_pos;
      gl_Position = u_matrix * vec4(a_pos.x + u_world, a_pos.y, 0.0, 1.0);
    }`;

  const FIELD_FS = `
    precision highp float;
    ${GRID_GLSL}
    uniform sampler2D u_data;
    uniform sampler2D u_ramp;
    uniform vec4 u_channel;
    varying vec2 v_merc;
    void main() {
      float value = dot(texture2D(u_data, gridUv(v_merc)), u_channel);
      vec4 c = texture2D(u_ramp, vec2(value, 0.5));
      gl_FragColor = vec4(c.rgb * c.a, c.a);
    }`;

  // A square over the whole viewport (for the particles' state and the trails).
  const QUAD_VS = `
    attribute vec2 a_pos;
    varying vec2 v_tex;
    void main() {
      v_tex = a_pos;
      gl_Position = vec4(1.0 - 2.0 * a_pos, 0.0, 1.0) * vec4(-1.0, -1.0, 1.0, 1.0);
    }`;

  const SCREEN_FS = `
    precision mediump float;
    uniform sampler2D u_screen;
    uniform float u_opacity;
    varying vec2 v_tex;
    void main() {
      vec4 c = texture2D(u_screen, v_tex);
      gl_FragColor = c * u_opacity;
    }`;

  // Particles' positions: 0-1 across the view's box in Mercator, two bytes each (webgl-wind's way).
  const DECODE = `
    vec2 decode(vec4 c) { return vec2(c.r / 255.0 + c.b, c.g / 255.0 + c.a); }
    vec4 encode(vec2 p) { return vec4(fract(p * 255.0), floor(p * 255.0) / 255.0); }`;

  const UPDATE_FS = `
    precision highp float;
    ${GRID_GLSL}
    ${DECODE}
    uniform sampler2D u_particles;
    uniform sampler2D u_wind;
    uniform vec4 u_box;        // x, y, width, height in Mercator
    uniform vec4 u_range;      // u lo, u hi, v lo, v hi
    uniform float u_speed;     // Mercator units per frame per m/s
    uniform float u_seed;
    uniform float u_drop;
    uniform float u_bump;
    varying vec2 v_tex;
    float rand(vec2 co) {
      vec3 r = fract(vec3(co.xy, 0.5) * vec3(12.9898, 78.233, 4375.85453));
      r += dot(r, r.yzx + 33.33);
      return fract((r.x + r.y) * r.z);
    }
    void main() {
      vec2 pos = decode(texture2D(u_particles, v_tex));
      vec2 merc = u_box.xy + pos * u_box.zw;
      vec2 w = texture2D(u_wind, gridUv(merc)).rg;
      vec2 vel = vec2(mix(u_range.x, u_range.y, w.x), mix(u_range.z, u_range.w, w.y));
      float speed = length(vel) / 40.0;
      // East is +x, north is -y in Mercator; the same scale both ways (it's conformal).
      pos = fract(pos + vec2(vel.x, -vel.y) * u_speed / u_box.zw);
      vec2 seed = (pos + v_tex) * u_seed;
      float drop = step(1.0 - u_drop - speed * u_bump, rand(seed));
      pos = mix(pos, vec2(rand(seed + 1.3), rand(seed + 2.1)), drop);
      gl_FragColor = encode(pos);
    }`;

  const DRAW_VS = `
    precision highp float;
    ${DECODE}
    attribute float a_index;
    uniform sampler2D u_particles;
    uniform float u_res;
    uniform vec4 u_box;
    uniform mat4 u_matrix;
    uniform float u_size;
    void main() {
      vec2 tex = vec2(fract(a_index / u_res), floor(a_index / u_res) / u_res) + 0.5 / u_res;
      vec2 merc = u_box.xy + decode(texture2D(u_particles, tex)) * u_box.zw;
      gl_PointSize = u_size;
      gl_Position = u_matrix * vec4(merc, 0.0, 1.0);
    }`;

  const DRAW_FS = `
    precision mediump float;
    uniform vec4 u_color;
    void main() { gl_FragColor = u_color; }`;

  // ---- GL helpers ----

  function program(gl, vs, fs) {
    const p = gl.createProgram();
    for (const [type, src] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fs]]) {
      const s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      gl.attachShader(p, s);
    }
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
    p.u = {};
    for (let i = 0; i < n; i++) {
      const name = gl.getActiveUniform(p, i).name;
      p.u[name] = gl.getUniformLocation(p, name);
    }
    p.a = (name) => gl.getAttribLocation(p, name);
    return p;
  }

  function texture(gl, filter, w, h, data, wrapX) {
    const t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, wrapX || gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
    if (data instanceof Uint8Array || data == null) {
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, data);
    } else {
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, data);
    }
    return t;
  }

  function buffer(gl, data) {
    const b = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, b);
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
    return b;
  }

  function attrib(gl, buf, loc, size) {
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, size, gl.FLOAT, false, 0, 0);
  }

  /**
   * The matrix from Mercator's 0-1 square to the screen: MapLibre 5 gives it as its projection
   * data's main matrix (its modelViewProjectionMatrix is in world pixels); earlier versions gave
   * the matrix itself.
   */
  function matrixOf(args) {
    if (args && args.defaultProjectionData) return args.defaultProjectionData.mainMatrix;
    return args && args.modelViewProjectionMatrix ? args.modelViewProjectionMatrix : args;
  }

  // ---- the layer ----

  /** Pictures by URL: an image and its texture once loaded. */
  const pictures = new Map();

  function picture(gl, url) {
    if (!url) return null;
    let p = pictures.get(url);
    if (!p) {
      p = { img: new Image(), tex: null, failed: false };
      p.img.crossOrigin = 'anonymous';
      p.img.onload = () => map && map.triggerRepaint();
      p.img.onerror = () => { p.failed = true; };
      p.img.src = url;
      pictures.set(url, p);
      // Keep a few (the steps either side of the one shown).
      while (pictures.size > 8) {
        const [k, old] = pictures.entries().next().value;
        if (old.tex) gl.deleteTexture(old.tex);
        pictures.delete(k);
      }
    }
    if (!p.tex && p.img.complete && p.img.naturalWidth > 0) {
      p.tex = texture(gl, gl.LINEAR, 0, 0, p.img, gl instanceof WebGL2RenderingContext ? gl.REPEAT : gl.CLAMP_TO_EDGE);
    }
    return p.tex;
  }

  const layer = {
    id: LAYER_ID,
    type: 'custom',
    renderingMode: '2d',

    onAdd(m, gl) {
      this.gl = gl;
      this.field = program(gl, FIELD_VS, FIELD_FS);
      this.screen = program(gl, QUAD_VS, SCREEN_FS);
      this.update = program(gl, QUAD_VS, UPDATE_FS);
      this.draw = program(gl, DRAW_VS, DRAW_FS);
      this.world = buffer(gl, new Float32Array([0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 1]));
      this.quad = buffer(gl, new Float32Array([0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 1]));
      const index = new Float32Array(RES * RES);
      for (let i = 0; i < index.length; i++) index[i] = i;
      this.index = buffer(gl, index);
      this.fbo = gl.createFramebuffer();
      this.ramps = {};
      this.resetParticles();
      this.box = null;
      this.moved = true;
      this.trailW = 0;
    },

    onRemove(m, gl) {
      for (const p of pictures.values()) if (p.tex) { gl.deleteTexture(p.tex); p.tex = null; }
      for (const t of [this.stateA, this.stateB, this.trailA, this.trailB]) if (t) gl.deleteTexture(t);
      for (const r of Object.values(this.ramps)) gl.deleteTexture(r);
      this.stateA = this.stateB = this.trailA = this.trailB = null;
    },

    resetParticles() {
      const gl = this.gl;
      const seed = new Uint8Array(RES * RES * 4);
      for (let i = 0; i < seed.length; i++) seed[i] = Math.floor(Math.random() * 256);
      if (this.stateA) gl.deleteTexture(this.stateA);
      if (this.stateB) gl.deleteTexture(this.stateB);
      this.stateA = texture(gl, gl.NEAREST, RES, RES, seed);
      this.stateB = texture(gl, gl.NEAREST, RES, RES, seed);
    },

    ramp(field) {
      const gl = this.gl;
      const key = JSON.stringify([field.range, field.sqrt, field.stops]);
      if (!this.ramps[key]) this.ramps[key] = texture(gl, gl.LINEAR, 256, 1, rampPixels(field));
      return this.ramps[key];
    },

    /** The view's box in Mercator (all of it when turned), for the particles. */
    viewBox() {
      const b = map.getBounds();
      const nw = maplibregl.MercatorCoordinate.fromLngLat([b.getWest(), Math.min(85, b.getNorth())]);
      const se = maplibregl.MercatorCoordinate.fromLngLat([b.getEast(), Math.max(-85, b.getSouth())]);
      return [nw.x, nw.y, Math.max(1e-9, se.x - nw.x), Math.max(1e-9, se.y - nw.y)];
    },

    trails(gl) {
      const w = gl.drawingBufferWidth, h = gl.drawingBufferHeight;
      if (w === this.trailW && h === this.trailH) return;
      if (this.trailA) gl.deleteTexture(this.trailA);
      if (this.trailB) gl.deleteTexture(this.trailB);
      this.trailA = texture(gl, gl.NEAREST, w, h, null);
      this.trailB = texture(gl, gl.NEAREST, w, h, null);
      this.trailW = w;
      this.trailH = h;
    },

    clearTrails(gl) {
      for (const t of [this.trailA, this.trailB]) {
        gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
        gl.clearColor(0, 0, 0, 0);
        gl.clear(gl.COLOR_BUFFER_BIT);
      }
    },

    /** Before the map's own drawing: the particles move, and draw their trails off screen. */
    prerender(gl, args) {
      if (!spec || !spec.wind) return;
      // Our own vertex state, not one of MapLibre's.
      if (gl.bindVertexArray) gl.bindVertexArray(null);
      const wind = picture(gl, spec.wt);
      if (!wind) return;
      const matrix = matrixOf(args);
      this.trails(gl);
      if (this.moved || !this.box) {
        // A new view: the particles start again over it, the old trails go.
        this.moved = false;
        this.box = this.viewBox();
        this.resetParticles();
        this.clearTrails(gl);
      }
      const [x0, y0, bw, bh] = this.box;
      const pxToMerc = 1 / (512 * Math.pow(2, map.getZoom()));
      const r = spec.ranges;
      gl.disable(gl.DEPTH_TEST);
      gl.disable(gl.STENCIL_TEST);
      gl.disable(gl.CULL_FACE);

      // 1. Move them.
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, this.stateB, 0);
      gl.viewport(0, 0, RES, RES);
      gl.disable(gl.BLEND);
      let p = this.update;
      gl.useProgram(p);
      attrib(gl, this.quad, p.a('a_pos'), 2);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, this.stateA);
      gl.uniform1i(p.u.u_particles, 0);
      gl.activeTexture(gl.TEXTURE1);
      gl.bindTexture(gl.TEXTURE_2D, wind);
      gl.uniform1i(p.u.u_wind, 1);
      gl.uniform4f(p.u.u_grid, spec.grid.width, spec.grid.height, spec.grid.west, spec.grid.north);
      gl.uniform4f(p.u.u_box, x0, y0, bw, bh);
      gl.uniform4f(p.u.u_range, r.u[0], r.u[1], r.v[0], r.v[1]);
      gl.uniform1f(p.u.u_speed, SPEED_PX * pxToMerc);
      gl.uniform1f(p.u.u_seed, Math.random());
      gl.uniform1f(p.u.u_drop, DROP);
      gl.uniform1f(p.u.u_bump, DROP_BUMP);
      gl.drawArrays(gl.TRIANGLES, 0, 6);
      [this.stateA, this.stateB] = [this.stateB, this.stateA];

      // 2. Their trails: the last frame's, fainter, and the particles where they are now.
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, this.trailB, 0);
      gl.viewport(0, 0, this.trailW, this.trailH);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      p = this.screen;
      gl.useProgram(p);
      attrib(gl, this.quad, p.a('a_pos'), 2);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, this.trailA);
      gl.uniform1i(p.u.u_screen, 0);
      gl.uniform1f(p.u.u_opacity, FADE);
      gl.drawArrays(gl.TRIANGLES, 0, 6);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      p = this.draw;
      gl.useProgram(p);
      attrib(gl, this.index, p.a('a_index'), 1);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, this.stateA);
      gl.uniform1i(p.u.u_particles, 0);
      gl.uniform1f(p.u.u_res, RES);
      gl.uniform4f(p.u.u_box, x0, y0, bw, bh);
      gl.uniformMatrix4fv(p.u.u_matrix, false, matrix);
      gl.uniform1f(p.u.u_size, 1.6 * (window.devicePixelRatio || 1));
      // The field's own particle colour (dark over clouds), else white over a field, dark blue on the map.
      const hex = /^#([0-9a-f]{6})$/i.exec((spec.field && spec.field.particles) || '');
      const c = hex ? [0, 2, 4].map((i) => parseInt(hex[1].substr(i, 2), 16) / 255).concat(0.5)
        : spec.field ? [1, 1, 1, 0.75] : [0.12, 0.16, 0.3, 0.75];
      gl.uniform4f(p.u.u_color, c[0] * c[3], c[1] * c[3], c[2] * c[3], c[3]);
      gl.drawArrays(gl.POINTS, 0, RES * RES);
      [this.trailA, this.trailB] = [this.trailB, this.trailA];
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    },

    render(gl, args) {
      try {
        this.renderField(gl, args);
      } catch (e) {
        this.error = String(e && e.message || e);
      }
    },

    renderField(gl, args) {
      if (!spec) return;
      if (gl.bindVertexArray) gl.bindVertexArray(null);
      const matrix = matrixOf(args);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      gl.disable(gl.DEPTH_TEST);
      gl.disable(gl.STENCIL_TEST);

      // The field, over the world and its copies either side.
      const field = spec.field;
      const data = field && picture(gl, field.source === 'wt' ? spec.wt : spec.cp);
      if (field && data) {
        const p = this.field;
        gl.useProgram(p);
        attrib(gl, this.world, p.a('a_pos'), 2);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, data);
        gl.uniform1i(p.u.u_data, 0);
        gl.activeTexture(gl.TEXTURE1);
        gl.bindTexture(gl.TEXTURE_2D, this.ramp(field));
        gl.uniform1i(p.u.u_ramp, 1);
        gl.uniform4f(p.u.u_grid, spec.grid.width, spec.grid.height, spec.grid.west, spec.grid.north);
        const ch = [0, 0, 0, 0];
        ch[field.channel] = 1;
        gl.uniform4f(p.u.u_channel, ch[0], ch[1], ch[2], ch[3]);
        gl.uniformMatrix4fv(p.u.u_matrix, false, matrix);
        for (const w of [-1, 0, 1]) {
          gl.uniform1f(p.u.u_world, w);
          gl.drawArrays(gl.TRIANGLES, 0, 6);
        }
      }

      // The wind's trails over it.
      if (spec.wind && this.trailA) {
        const p = this.screen;
        gl.useProgram(p);
        attrib(gl, this.quad, p.a('a_pos'), 2);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, this.trailA);
        gl.uniform1i(p.u.u_screen, 0);
        gl.uniform1f(p.u.u_opacity, 0.85);
        gl.drawArrays(gl.TRIANGLES, 0, 6);
        map.triggerRepaint(); // they keep moving
      }
    },
  };

  /** Puts the layer in the map (under its labels), when there's something to show. */
  function place() {
    if (!map) return;
    // (isStyleLoaded() waits for every tile too: the style itself is enough.)
    let has;
    try {
      has = !!map.getLayer(LAYER_ID);
      if (!spec) {
        if (has) map.removeLayer(LAYER_ID);
        return;
      }
      if (!has) {
        const labels = (map.getStyle().layers || []).find((l) => l.type === 'symbol');
        map.addLayer(layer, labels ? labels.id : undefined);
      }
    } catch (e) {
      // The style isn't there yet: when it is.
      map.once('styledata', place);
      return;
    }
    layer.moved = true;
    map.triggerRepaint();
  }

  return {
    /** For checking it in a browser: what's there. */
    state() {
      return JSON.stringify({
        spec: !!spec, layer: !!(map && map.getLayer(LAYER_ID)), gl: !!layer.gl,
        pictures: [...pictures.entries()].map(([k, p]) => [k, p.img.complete, p.img.naturalWidth, !!p.tex, p.failed]),
        error: layer.error || null,
      });
    },
    /** Shows [s] (see above) on [m], or nothing (null). */
    set(m, s) {
      map = m;
      spec = s;
      if (hooked !== m) {
        hooked = m;
        // A new style drops custom layers: put it back.
        m.on('style.load', place);
        // The view moved: the wind's particles start again over the new one.
        m.on('move', () => { layer.moved = true; });
        m.on('resize', () => { layer.moved = true; });
      }
      place();
    },
  };
})();
