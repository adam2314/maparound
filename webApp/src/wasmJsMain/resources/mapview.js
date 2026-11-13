// MapAround web: the MapLibre GL JS map, which sits behind the app's Compose canvas (the canvas
// is transparent where the map is). Kotlin (WebMap.kt) drives it: style, overlay data, camera,
// and the gestures, which Compose receives and passes on here. Map tiles of the offline map come
// from the shared Kotlin tile service through the "maparound:" protocol.
(function () {
  'use strict';
  const SEP = '\u001F';
  const SYMBOL = 'sym:';
  let map = null;
  let container = null;
  let tileHandler = null;
  let symbolBase = 'mapsforge/';
  /** The latest data of each GeoJSON source, applied again after a style change. */
  const geo = new Map();

  function dpr() { return window.devicePixelRatio || 1; }

  // ---- tiles from Kotlin ------------------------------------------------------------------------

  maplibregl.addProtocol('maparound', (params) => new Promise((resolve, reject) => {
    const bytes = tileHandler ? tileHandler(params.url) : null;
    if (bytes) {
      resolve({ data: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) });
    } else {
      reject(new Error('No tile: ' + params.url));
    }
  }));

  // ---- marker images, drawn from their names (MarkerIcons.kt; as Android's MarkerBitmaps) ------

  const DARK = 'rgb(40,40,40)';
  const RED = 'rgb(213,0,0)';

  function argb(hex) {
    const v = parseInt(hex, 16);
    if (isNaN(v)) return 'rgba(128,128,128,1)';
    const a = ((v >>> 24) & 255) / 255;
    return 'rgba(' + ((v >>> 16) & 255) + ',' + ((v >>> 8) & 255) + ',' + (v & 255) + ',' + a + ')';
  }

  function canvas(wDp, hDp) {
    const d = dpr();
    const c = document.createElement('canvas');
    c.width = Math.ceil(wDp * d);
    c.height = Math.ceil(hDp * d);
    const g = c.getContext('2d');
    g.scale(d, d);
    return { c: c, g: g };
  }

  function font(size, bold) {
    return (bold ? 'bold ' : '') + size + 'px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
  }

  function circle(g, x, y, r, color) {
    g.beginPath();
    g.arc(x, y, r, 0, Math.PI * 2);
    g.fillStyle = color;
    g.fill();
  }

  function centredText(g, text, x, y, size, bold, color) {
    g.font = font(size, bold);
    g.fillStyle = color;
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillText(text, x, y);
  }

  function dot() {
    const k = canvas(24, 24);
    circle(k.g, 12, 13, 11, 'rgba(0,0,0,0.24)');
    circle(k.g, 12, 12, 10, '#fff');
    circle(k.g, 12, 12, 7, 'rgb(30,136,229)');
    return k.c;
  }

  function pin() {
    const w = 28, h = 40, r = w / 2;
    const k = canvas(w, h);
    const g = k.g;
    g.fillStyle = 'rgb(211,47,47)';
    g.beginPath();
    g.moveTo(r, h);
    g.lineTo(r - r * 0.62, r * 1.55);
    g.lineTo(r + r * 0.62, r * 1.55);
    g.closePath();
    g.fill();
    circle(g, r, r, r - 1, 'rgb(211,47,47)');
    circle(g, r, r, r * 0.38, '#fff');
    return k.c;
  }

  function disc(size, shadow, rings, text, textSize) {
    const k = canvas(size, size);
    const r = size / 2;
    if (shadow) circle(k.g, r, r + 1, r - 1, 'rgba(0,0,0,0.27)');
    for (const [radius, color] of rings) circle(k.g, r, r, radius, color);
    centredText(k.g, text, r, r + 0.5, textSize, true, '#fff');
    return k.c;
  }

  /** A round marker with an optional label below; the circle's centre is the image's centre. */
  function badge(o) {
    const probe = canvas(1, 1).g;
    probe.font = font(11, true);
    const labelW = o.label ? probe.measureText(o.label).width + 8 : 0;
    const labelH = o.label ? 16 : 0;
    const gap = o.label ? 3 : 0;
    const dia = 2 * (o.radius + o.ringWidth);
    const content = dia + gap + labelH;
    const topPad = content - dia;
    const w = Math.ceil(Math.max(dia, labelW)) + 2;
    const h = Math.ceil(topPad + content);
    const k = canvas(w, h);
    const g = k.g;
    g.globalAlpha = o.alpha;
    const cx = w / 2, cy = topPad + dia / 2;
    circle(g, cx, cy, dia / 2, o.ring);
    circle(g, cx, cy, o.radius, o.body);
    if (o.flag) {
      g.strokeStyle = '#fff';
      g.lineWidth = 2;
      g.beginPath(); g.moveTo(cx - 4, cy - 7); g.lineTo(cx - 4, cy + 7); g.stroke();
      g.fillStyle = '#fff';
      g.beginPath(); g.moveTo(cx - 4, cy - 7); g.lineTo(cx + 7, cy - 3.5); g.lineTo(cx - 4, cy); g.closePath(); g.fill();
    } else if (o.symbol) {
      centredText(g, o.symbol, cx, cy + 0.5, o.symbolSize, o.symbolBold, o.symbolColor);
    }
    if (o.label) {
      const top = cy + dia / 2 + gap;
      g.fillStyle = 'rgba(255,255,255,0.9)';
      g.beginPath();
      if (g.roundRect) g.roundRect(cx - labelW / 2, top, labelW, labelH, 4); else g.rect(cx - labelW / 2, top, labelW, labelH);
      g.fill();
      centredText(g, o.label, cx, top + labelH / 2 + 0.5, 11, true, o.labelColor);
    }
    return k.c;
  }

  function party(f) {
    const kind = f[1];
    const sos = kind === 'sos';
    const body = kind === 'meet' ? 'rgb(198,40,40)' : kind === 'radio' ? 'rgb(97,97,97)' : argb(f[2]);
    const letter = sos ? '!' : (f[3] || '').slice(0, 1).toUpperCase();
    return badge({
      ring: sos ? RED : '#fff', ringWidth: sos ? 4 : 2, body: body, radius: 13,
      symbol: kind === 'meet' ? '' : letter, symbolSize: 13, symbolBold: true,
      symbolColor: (f.length >= 7 && kind === 'member') ? argb(f[6]) : '#fff',
      label: f[3] || '', labelColor: sos ? RED : DARK, alpha: f[4] === '1' ? 140 / 255 : 1, flag: kind === 'meet',
    });
  }

  function marker(name) {
    const f = name.split(SEP);
    switch (f[0]) {
      case 'me': return dot();
      case 'pin': return pin();
      case 'place': return f.length >= 4 ? badge({
        ring: argb(f[1]), ringWidth: 2.5, body: '#fff', radius: 12, symbol: f[2], symbolSize: 14, symbolBold: false,
        symbolColor: DARK, label: f[3], labelColor: DARK, alpha: 1,
      }) : null;
      case 'party': return f.length >= 5 ? party(f) : null;
      case 'wp': {
        if (f.length < 3) return null;
        const fill = f[2] === '0' ? 'rgb(46,125,50)' : f[2] === '2' ? 'rgb(198,40,40)' : 'rgb(106,27,154)';
        return disc(26, false, [[12, '#fff'], [10, fill]], f[1], 12);
      }
      case 'letter': return f.length >= 3 ? disc(28, true, [[12, '#fff'], [10, argb(f[2])]], f[1], 14) : null;
      default: return null;
    }
  }

  function addCanvas(id, c) {
    if (!c || map.hasImage(id)) return;
    const data = c.getContext('2d').getImageData(0, 0, c.width, c.height);
    map.addImage(id, data, { pixelRatio: dpr() });
  }

  /** A symbol of the map style (an SVG of the mapsforge themes), 20 points high. */
  function symbol(id) {
    const img = new Image();
    img.onload = () => {
      if (!map || map.hasImage(id)) return;
      const h = 20;
      const ratio = img.naturalWidth && img.naturalHeight ? img.naturalWidth / img.naturalHeight : 1;
      const k = canvas(Math.max(1, h * ratio), h);
      k.g.drawImage(img, 0, 0, h * ratio, h);
      addCanvas(id, k.c);
    };
    img.src = symbolBase + id.slice(SYMBOL.length);
  }

  function onMissingImage(e) {
    const id = e.id;
    if (!id || map.hasImage(id)) return;
    if (id.startsWith(SYMBOL)) symbol(id);
    else addCanvas(id, marker(id));
  }

  // ---- the map --------------------------------------------------------------------------------

  function applyGeo() {
    for (const [id, data] of geo) {
      const s = map.getSource(id);
      if (s && s.setData) s.setData(data);
    }
  }

  function tapLayers() {
    const style = map.getStyle();
    return style && style.layers ? style.layers.map(l => l.id).filter(id => id.startsWith('tap-')) : [];
  }

  // ---- gestures --------------------------------------------------------------------------------
  // The browser's pointer events (every finger), watched on the whole page. A gesture moves the
  // map only once the app has claimed it (Kotlin: the press reached the map, not a button or sheet
  // over it), and not while a long press holds it.

  const SLOP = 8;
  const pointers = new Map(); // id -> { x, y } within the map, CSS pixels
  let claimed = false, holding = false, moved = false, notified = false;
  let last = null; // { cx, cy, span, angle } at the last applied move
  let samples = []; // recent { t, cx, cy } for the fling
  let onGesture = null;

  function local(e) {
    const r = container.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  function shape() {
    const ps = [...pointers.values()];
    const n = ps.length;
    const cx = ps.reduce((s, p) => s + p.x, 0) / n;
    const cy = ps.reduce((s, p) => s + p.y, 0) / n;
    if (n < 2) return { n: n, cx: cx, cy: cy, span: 0, angle: 0 };
    const a = ps[0], b = ps[1];
    return { n: n, cx: cx, cy: cy, span: Math.hypot(b.x - a.x, b.y - a.y), angle: Math.atan2(b.y - a.y, b.x - a.x) * 180 / Math.PI };
  }

  function apply() {
    const now = shape();
    if (!last || last.n !== now.n) { last = now; return; } // finger count changed: start over from here
    if (!moved) {
      if (now.n < 2 && Math.hypot(now.cx - start.cx, now.cy - start.cy) < SLOP) return;
      moved = true;
    }
    if (!notified) { notified = true; if (onGesture) onGesture(); }
    const dx = now.cx - last.cx, dy = now.cy - last.cy;
    if (dx || dy) map.panBy([-dx, -dy], { animate: false });
    if (now.n >= 2 && last.span > 0 && now.span > 0) {
      let turn = now.angle - last.angle;
      if (turn > 180) turn -= 360;
      if (turn < -180) turn += 360;
      map.easeTo({
        zoom: map.getZoom() + Math.log2(now.span / last.span),
        bearing: map.getBearing() - turn,
        around: map.unproject([now.cx, now.cy]),
        duration: 0,
      });
    }
    last = now;
    const t = performance.now();
    samples.push({ t: t, cx: now.cx, cy: now.cy, n: now.n });
    while (samples.length && t - samples[0].t > 120) samples.shift();
  }

  let start = { cx: 0, cy: 0 };

  function down(e) {
    if (!map || !container || container.style.display === 'none') return;
    if (pointers.size === 0) {
      claimed = false; holding = false; moved = false; notified = false; last = null; samples = [];
      const p = local(e);
      start = { cx: p.x, cy: p.y };
    }
    pointers.set(e.pointerId, local(e));
    last = null;
  }

  function move(e) {
    if (!pointers.has(e.pointerId)) return;
    pointers.set(e.pointerId, local(e));
    if (claimed && !holding) apply();
  }

  function up(e) {
    if (!pointers.has(e.pointerId)) return;
    pointers.delete(e.pointerId);
    last = null;
    if (pointers.size > 0) return;
    // A flick keeps the map gliding (one finger or the mouse).
    if (claimed && !holding && moved && samples.length >= 2) {
      const a = samples[0], b = samples[samples.length - 1];
      const dt = (b.t - a.t) / 1000;
      if (dt > 0 && a.n === 1 && b.n === 1) {
        const vx = (b.cx - a.cx) / dt, vy = (b.cy - a.cy) / dt;
        if (Math.hypot(vx, vy) > 150) map.panBy([-vx * 0.3, -vy * 0.3], { duration: 700, easing: t => 1 - Math.pow(1 - t, 3) });
      }
    }
    claimed = false;
  }

  // The page itself never zooms (it would stretch the app's toolbars): Safari's pinch gesture
  // events, two-finger touch moves, and a trackpad pinch (which comes as ctrl + wheel, and zooms
  // the map here instead).
  const noPageZoom = (e) => e.preventDefault();
  document.addEventListener('gesturestart', noPageZoom, { passive: false });
  document.addEventListener('gesturechange', noPageZoom, { passive: false });
  document.addEventListener('touchmove', (e) => { if (e.touches.length > 1) e.preventDefault(); }, { passive: false });
  window.addEventListener('wheel', (e) => {
    if (!e.ctrlKey) return;
    e.preventDefault();
    if (!map || !container || container.style.display === 'none') return;
    const r = container.getBoundingClientRect();
    const x = e.clientX - r.left, y = e.clientY - r.top;
    if (x < 0 || y < 0 || x > r.width || y > r.height) return;
    if (onGesture) onGesture();
    map.easeTo({ zoom: map.getZoom() - e.deltaY * 0.01, around: map.unproject([x, y]), duration: 0 });
  }, { passive: false });
  // The screen's pixel ratio changes (browser zoom, another monitor): the map follows.
  let ratio = dpr();
  window.addEventListener('resize', () => {
    if (map && dpr() !== ratio) {
      ratio = dpr();
      if (map.setPixelRatio) map.setPixelRatio(ratio);
    }
  });

  window.addEventListener('pointerdown', down, true);
  window.addEventListener('pointermove', move, true);
  window.addEventListener('pointerup', up, true);
  window.addEventListener('pointercancel', up, true);

  window.MapAroundMap = {
    create(containerId, styleJson, symbols, onTile, onUserGesture) {
      container = document.getElementById(containerId);
      symbolBase = symbols;
      tileHandler = onTile;
      onGesture = onUserGesture;
      map = new maplibregl.Map({
        container: container,
        style: JSON.parse(styleJson),
        interactive: false,
        attributionControl: false,
        fadeDuration: 150,
        maxPitch: 60,
        pixelRatio: dpr(),
      });
      map.on('styleimagemissing', onMissingImage);
      map.on('style.load', applyGeo);
    },
    setStyle(json) {
      map.setStyle(JSON.parse(json), { diff: false });
    },
    setGeoJson(id, json) {
      const data = JSON.parse(json);
      geo.set(id, data);
      const s = map.getSource(id);
      if (s && s.setData) s.setData(data);
    },
    camera() {
      const c = map.getCenter();
      return [c.lat, c.lng, map.getZoom(), map.getBearing()].join(',');
    },
    height() { return container ? container.clientHeight : 0; },
    move(lat, lon, zoom, animated) {
      const o = { center: [lon, lat], zoom: zoom };
      if (animated) map.easeTo(Object.assign(o, { duration: 600 })); else map.jumpTo(o);
    },
    fitBounds(s, w, n, e, animated) {
      map.fitBounds([[w, s], [e, n]], { padding: 48, animate: animated, maxZoom: 17 });
    },
    /** Where the map is on the page (CSS pixels); hidden when the map screen is off. */
    place(left, top, width, height) {
      const st = container.style;
      st.left = left + 'px'; st.top = top + 'px'; st.width = width + 'px'; st.height = height + 'px';
      st.display = 'block';
      map.resize();
    },
    hide() { container.style.display = 'none'; },

    // ---- gestures, positions in CSS pixels within the map ----
    /** The press that's down now reached the map (not something over it): the map follows it. */
    claim() { claimed = true; map.stop(); apply(); },
    /** A long press holds the map still (its drag goes to the app). */
    hold(on) { holding = on; },
    /** Whether the current gesture has moved the map (so it's not a tap). */
    moved() { return moved || pointers.size > 1; },
    zoomAt(delta, x, y, animated) {
      map.easeTo({ zoom: map.getZoom() + delta, around: map.unproject([x, y]), duration: animated ? 300 : 0 });
    },
    northUp() { map.easeTo({ bearing: 0, pitch: 0, duration: 400 }); },
    /** "lat,lon" of the point (x, y). */
    lngLat(x, y) {
      const p = map.unproject([x, y]);
      return p.lat + ',' + p.lng;
    },
    /** The `tap` property of the topmost marker at (x, y), or "". */
    tapped(x, y) {
      const layers = tapLayers();
      if (!layers.length) return '';
      const r = 10;
      const f = map.queryRenderedFeatures([[x - r, y - r], [x + r, y + r]], { layers: layers });
      for (const it of f) if (it.properties && it.properties.tap) return String(it.properties.tap);
      return '';
    },
  };
})();
