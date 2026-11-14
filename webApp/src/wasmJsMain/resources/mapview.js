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

  // Hebrew and Arabic names: MapLibre GL JS needs this plugin to put right-to-left text in order
  // (MapLibre Native does it itself, on the phones), else they read backwards. From the app's own
  // files, so it works offline; loaded the first time such a name shows.
  if (maplibregl.getRTLTextPluginStatus && maplibregl.getRTLTextPluginStatus() === 'unavailable') {
    try {
      const p = maplibregl.setRTLTextPlugin('mapbox-gl-rtl-text.js', true);
      if (p && p.catch) p.catch((e) => console.warn('MapAround: no right-to-left text', e));
    } catch (e) {
      console.warn('MapAround: no right-to-left text', e);
    }
  }

  // ---- the tile worker (tiles/tiles-worker.js): the downloaded maps, in the browser's storage ----

  const tiles = { worker: null, next: 1, pending: new Map(), imports: new Map(), maps: [], onMaps: null };

  function startTiles(url, onMaps) {
    tiles.onMaps = onMaps;
    try {
      if (!('storage' in navigator) || !navigator.storage.getDirectory) throw new Error('no file storage');
      tiles.worker = new Worker(url);
    } catch (e) {
      console.warn('MapAround: no tile worker (' + e.message + '); downloaded maps are not available');
      tiles.worker = null;
      return false;
    }
    tiles.worker.onmessage = (e) => {
      const m = e.data;
      switch (m.type) {
        case 'tile': {
          const p = tiles.pending.get(m.id);
          if (p) {
            tiles.pending.delete(m.id);
            if (m.data) p.resolve({ data: m.data }); else p.reject(new Error('No tile'));
          }
          break;
        }
        case 'maps':
          tiles.maps = m.maps || [];
          if (tiles.onMaps) tiles.onMaps();
          break;
        case 'progress': {
          const i = tiles.imports.get(m.id);
          if (i && i.progress) i.progress(m.done, m.total);
          break;
        }
        case 'imported': {
          const i = tiles.imports.get(m.id);
          tiles.imports.delete(m.id);
          if (i) i.done(m.name || null, m.error || null);
          break;
        }
      }
    };
    tiles.worker.onerror = (e) => {
      console.warn('MapAround: tile worker failed', e.message);
      tiles.worker = null;
      for (const p of tiles.pending.values()) p.reject(new Error('Tile worker failed'));
      tiles.pending.clear();
    };
    return true;
  }

  // The offline map's tiles: from the worker; without one, from Kotlin on the page (the built-in world).
  maplibregl.addProtocol('maparound', (params) => new Promise((resolve, reject) => {
    if (tiles.worker) {
      const id = tiles.next++;
      tiles.pending.set(id, { resolve: resolve, reject: reject });
      tiles.worker.postMessage({ type: 'tile', id: id, path: params.url.replace(/^maparound:\/\/t/, '') });
      return;
    }
    const bytes = tileHandler ? tileHandler(params.url) : null;
    if (bytes) {
      resolve({ data: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) });
    } else {
      reject(new Error('No tile: ' + params.url));
    }
  }));

  window.MapAroundTiles = {
    start: startTiles,
    available() { return !!tiles.worker; },
    count() { return tiles.maps.length; },
    name(i) { return tiles.maps[i].name; },
    size(i) { return tiles.maps[i].size; },
    mtime(i) { return tiles.maps[i].mtime; },
    header(i) { return tiles.maps[i].header; },
    remove(name) { if (tiles.worker) tiles.worker.postMessage({ type: 'delete', name: name }); },
    /** An elevation file (a Uint8Array, handed over) for the worker's contour lines and hill shading; null: it's gone. */
    dem(name, bytes) {
      if (!tiles.worker) return;
      if (bytes) tiles.worker.postMessage({ type: 'dem', name: name, data: bytes.buffer }, [bytes.buffer]);
      else tiles.worker.postMessage({ type: 'dem', name: name, data: null });
    },
    /** The browser's file chooser for a .map file, copied into the storage by the worker. */
    pickAndImport(onStart, onProgress, onDone) {
      if (!tiles.worker) { onDone(null, 'This browser has no file storage for maps'); return; }
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = '.map';
      input.addEventListener('change', () => {
        const f = input.files && input.files[0];
        if (!f) return;
        const id = tiles.next++;
        tiles.imports.set(id, { progress: onProgress, done: onDone });
        onStart(f.name, f.size);
        tiles.worker.postMessage({ type: 'import', id: id, file: f });
      });
      input.click();
    },
    /** An ordinary browser download (no CORS needed): the user imports the file afterwards. */
    download(url) {
      const a = document.createElement('a');
      a.href = url;
      a.rel = 'noopener';
      document.body.appendChild(a);
      a.click();
      a.remove();
    },
    /** Free space the browser gives the site, bytes (−1: unknown). */
    estimate(onDone) {
      if (navigator.storage && navigator.storage.estimate) {
        navigator.storage.estimate().then((e) => onDone((e.quota || 0) - (e.usage || 0)), () => onDone(-1));
      } else {
        onDone(-1);
      }
    },
  };

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
  /** A mouse drag that turns and tilts the 3D view (right button, or with ctrl, shift or alt). */
  let turning = false;
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
    if (view3d && now.n === 1 && turning) {
      map.jumpTo({ bearing: map.getBearing() - dx * 0.4, pitch: tilt3d(dy) });
    } else if (view3d && now.n >= 2) {
      // Two fingers in 3D: up and down tilt the view (pinch and twist below), they don't move it.
      if (dy) map.jumpTo({ pitch: tilt3d(dy) });
    } else if (dx || dy) {
      map.panBy([-dx, -dy], { animate: false });
    }
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
    if (view3d) keepClear();
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
      turning = e.pointerType === 'mouse' && (e.button === 2 || e.ctrlKey || e.shiftKey || e.altKey);
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
    if (claimed && !holding && moved && !turning && samples.length >= 2) {
      const a = samples[0], b = samples[samples.length - 1];
      const dt = (b.t - a.t) / 1000;
      if (dt > 0 && a.n === 1 && b.n === 1) {
        const vx = (b.cx - a.cx) / dt, vy = (b.cy - a.cy) / dt;
        if (Math.hypot(vx, vy) > 150) map.panBy([-vx * 0.3, -vy * 0.3], { duration: 700, easing: t => 1 - Math.pow(1 - t, 3) });
      }
    }
    claimed = false;
    if (view3d) settle3d(null); // the gesture's over (a flick: when it stops)
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

  // The scale is the app's (its zoom slider's thumb), not drawn here. Right to left (Hebrew): kept
  // for what the map itself may place on a side.
  let rtl = false;

  // ---- where the map is, in the page's address: #map=zoom/lat/lon ----------------------------------
  // As on openstreetmap.org (its zoom, the app's: one more than MapLibre's), so a link or a reload
  // opens the same view; other parts of the address after # are kept.

  const MIN_ZOOM = 2, MAX_ZOOM = 21; // the app's (MapLibreSurface)

  function hashView() {
    const m = /(?:^#|&)map=(\d+(?:\.\d+)?)\/(-?\d+(?:\.\d+)?)\/(-?\d+(?:\.\d+)?)(?:&|$)/.exec(location.hash);
    if (!m) return null;
    const zoom = parseFloat(m[1]), lat = parseFloat(m[2]), lon = parseFloat(m[3]);
    if (!(Math.abs(lat) <= 85.06 && Math.abs(lon) <= 180)) return null;
    return { center: [lon, lat], zoom: Math.min(Math.max(zoom, MIN_ZOOM), MAX_ZOOM) - 1 };
  }

  /** The view in the address the page was opened with: the map opens there, not where it was last. */
  let startView = hashView();
  /** The view the new map takes instead of the first place the app puts it (its last position). */
  let firstView = null;

  function writeHash() {
    if (!map) return;
    const c = map.getCenter().wrap();
    const z = map.getZoom() + 1;
    const digits = Math.max(0, Math.ceil(Math.log2(Math.max(z, 1)))); // openstreetmap.org's precision
    const view = 'map=' + (Math.round(z * 100) / 100) + '/' + c.lat.toFixed(digits) + '/' + c.lng.toFixed(digits);
    const rest = location.hash.replace(/^#/, '').split('&').filter((p) => p && !p.startsWith('map='));
    const h = '#' + [view].concat(rest).join('&');
    if (h === location.hash) return;
    try {
      history.replaceState(history.state, '', h);
    } catch (e) {
      // Too many address changes too fast (Safari limits them): the next one catches up.
    }
  }

  /**
   * The pin (MapStyle.PIN's data) in the address as openstreetmap.org's ?mlat=…&mlon=…, so the
   * address opens the same pin; no pin, none in the address.
   */
  const PIN_SOURCE = 'mr-pin';

  function writePin(data) {
    const f = data && data.features && data.features[0];
    const c = f && f.geometry && f.geometry.type === 'Point' ? f.geometry.coordinates : null;
    const q = new URLSearchParams(location.search);
    if (c) {
      q.set('mlat', String(+c[1].toFixed(6)));
      q.set('mlon', String(+c[0].toFixed(6)));
    } else {
      q.delete('mlat');
      q.delete('mlon');
    }
    const s = q.toString();
    const url = location.pathname + (s ? '?' + s : '') + location.hash;
    if (url === location.pathname + location.search + location.hash) return;
    try {
      history.replaceState(history.state, '', url);
    } catch (e) {
      // Too many address changes too fast: the next one catches up.
    }
  }

  // Another view typed or opened in the address bar.
  window.addEventListener('hashchange', () => {
    const v = hashView();
    if (!v) return;
    if (map && container && container.style.display !== 'none') {
      if (onGesture) onGesture(); // as if the user moved the map: it stops following the position
      map.jumpTo(v);
    } else {
      startView = v; // the map screen isn't showing: the next map opens there
    }
  });

  // ---- the 3D view: the same map on the relief, a globe when zoomed out ------------------------------
  // MapLibre's own terrain from online elevation tiles (Mapzen's, on Amazon's servers), with the
  // relief shaded, buildings raised from the downloaded maps, and satellite imagery on request.
  // When the 3D view closes, the 2D map shows where it was looking, flat and north up.

  const DEM3D = 'mr3d-dem', SHADE3D = 'mr3d-shade', SAT3D = 'mr3d-sat', BUILDINGS3D = 'mr3d-buildings';
  const TERRARIUM = 'https://elevation-tiles-prod.s3.amazonaws.com/terrarium/{z}/{x}/{y}.png';
  const IMAGERY = 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}';
  const OSM_SOURCE = 'osm'; // MapStyle.OSM_SOURCE: the downloaded maps
  const MAX_PITCH_2D = 60, MAX_PITCH_3D = 80;
  /** Eases in and out (smoothstep), for the 3D view's turns and tilts. */
  const SMOOTH = (t) => t * t * (3 - 2 * t);
  /** Around the globe: space (the atmosphere's glow is MapLibre's). */
  const SPACE = 'radial-gradient(ellipse at 50% 60%, #1b2a4a 0%, #0a1020 70%)';
  /** While the 3D view is open: its settings, and the 2D view to go back to. */
  let view3d = null;
  /** Navigation's view is on (tilted, turned, padded). */
  let navigating = false;
  let exit3dTimer = null;

  function firstLayer(types) {
    const l = map.getStyle().layers.find((x) => types.indexOf(x.type) >= 0);
    return l ? l.id : undefined;
  }

  function dem(id) {
    if (!map.getSource(id)) map.addSource(id, { type: 'raster-dem', tiles: [TERRARIUM], encoding: 'terrarium', tileSize: 256, maxzoom: 15 });
  }

  function apply3d() {
    const v = view3d;
    if (!v || !map) return;
    try {
      for (const id of [SAT3D, SHADE3D, BUILDINGS3D]) if (map.getLayer(id)) map.removeLayer(id);
      dem(DEM3D);
      map.setTerrain({ source: DEM3D, exaggeration: v.exaggeration });
      map.setProjection({ type: 'globe' });
      map.setSky({
        'sky-color': '#5d97d6',
        'horizon-color': '#dceaf6',
        'fog-color': '#eef3f6',
        'sky-horizon-blend': 0.6,
        'horizon-fog-blend': 0.6,
        'fog-ground-blend': 0.7,
        'atmosphere-blend': ['interpolate', ['linear'], ['zoom'], 0, 1, 6, 1, 9, 0],
      });
      if (v.satellite) {
        // Imagery instead of the map, under its names and the app's own markers.
        if (!map.getSource(SAT3D)) map.addSource(SAT3D, { type: 'raster', tiles: [IMAGERY], tileSize: 256, maxzoom: 19 });
        map.addLayer({ id: SAT3D, type: 'raster', source: SAT3D }, firstLayer(['symbol']));
      } else {
        // The relief shaded, under the roads, paths and names (its own source, as MapLibre prefers).
        dem(SHADE3D);
        map.addLayer({
          id: SHADE3D,
          type: 'hillshade',
          source: SHADE3D,
          paint: { 'hillshade-exaggeration': 0.35, 'hillshade-shadow-color': 'rgba(60,45,30,0.55)', 'hillshade-highlight-color': 'rgba(255,255,255,0.25)' },
        }, firstLayer(['line', 'symbol']));
      }
      if (v.buildings && map.getSource(OSM_SOURCE)) {
        map.addLayer({
          id: BUILDINGS3D,
          type: 'fill-extrusion',
          source: OSM_SOURCE,
          'source-layer': 'area',
          minzoom: 14,
          filter: ['has', 'building'],
          paint: {
            'fill-extrusion-color': '#d6cbc1',
            'fill-extrusion-opacity': 0.9,
            'fill-extrusion-height': buildingTop(v.exaggeration),
            'fill-extrusion-base': buildingBase(v.exaggeration),
          },
        }, firstLayer(['symbol']));
      }
    } catch (e) {
      // The style is still loading: style.load applies it.
    }
  }

  // MapLibre stands a building on the terrain at its middle (and 10 m below); on a slope its uphill
  // side sank into the hill. The tiles say how the ground under its corners varies from its middle
  // (relief_up / relief_down, from the elevation data); the terrain is drawn exaggerated: the roof
  // goes up to its height above the highest corner, the walls down past the lowest.
  function buildingHeight() {
    return ['coalesce', ['to-number', ['get', 'height']], ['*', 3, ['to-number', ['get', 'building:levels']]], 7];
  }
  function buildingTop(exaggeration) {
    return ['+', buildingHeight(), ['*', exaggeration, ['coalesce', ['get', 'relief_up'], 0]]];
  }
  function buildingBase(exaggeration) {
    return ['-', ['coalesce', ['to-number', ['get', 'min_height']], 0], ['*', exaggeration, ['coalesce', ['get', 'relief_down'], 0]]];
  }

  // ---- the 3D camera above the ground ----
  // MapLibre holds the height of the view's middle from the start of an animation until one of its
  // own gestures ends, and the gestures here are the app's: entering 3D before the elevation had
  // arrived left the middle at sea level and the camera inside the mountain (only sky). Its own
  // check that the camera is above the ground doesn't work on the globe. Here: after each move and
  // as the elevation arrives, the middle goes onto the ground, and where the camera would be in a
  // hill (a steep slope behind it, more relief, tilting low) the view tilts up.

  const EARTH_R = 6371008.8;

  /** The ground's height at the view's middle (metres, exaggerated as drawn); NaN before it's loaded. */
  function middleGround() {
    const c = map.getCenter();
    const h = map.terrain.getElevationForLngLatZoom(c, Math.max(0, Math.floor(map.getZoom())));
    return h ? h : NaN; // 0: no elevation there yet
  }

  // The ground behind the view (under the camera) from the relief's own elevation tiles, read here:
  // MapLibre's reading is wrong where it hasn't loaded the tile, and it loads only the view's.
  const groundTiles = new Map(); // "z/x/y" -> heights (metres, 256 × 256), 'loading', or null (none)

  function groundTile(z, x, y) {
    const key = z + '/' + x + '/' + y;
    if (groundTiles.has(key)) return groundTiles.get(key);
    groundTiles.set(key, 'loading');
    if (groundTiles.size > 48) groundTiles.delete(groundTiles.keys().next().value);
    const url = TERRARIUM.replace('{z}', z).replace('{x}', x).replace('{y}', y);
    fetch(url)
      .then((r) => (r.ok ? r.blob() : Promise.reject(new Error(String(r.status)))))
      .then((b) => createImageBitmap(b, { colorSpaceConversion: 'none', premultiplyAlpha: 'none' }))
      .then((bmp) => {
        const cv = document.createElement('canvas');
        cv.width = 256; cv.height = 256;
        const g = cv.getContext('2d', { willReadFrequently: true });
        g.drawImage(bmp, 0, 0, 256, 256);
        const px = g.getImageData(0, 0, 256, 256).data;
        const h = new Float32Array(256 * 256);
        for (let i = 0; i < h.length; i++) h[i] = px[4 * i] * 256 + px[4 * i + 1] + px[4 * i + 2] / 256 - 32768;
        groundTiles.set(key, h);
        settleSoon();
      })
      .catch(() => groundTiles.set(key, null));
    return 'loading';
  }

  /** The ground's height (metres, exaggerated as drawn) at (lng, lat); NaN while its tile loads. */
  function groundAt(lng, lat) {
    const z = Math.max(0, Math.min(14, Math.round(map.getZoom()) - 2));
    const n = 1 << z, s = Math.sin(lat * Math.PI / 180);
    const x = (lng + 180) / 360 * n, y = (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * n;
    const tx = Math.floor(x), ty = Math.floor(y);
    const t = groundTile(z, ((tx % n) + n) % n, Math.max(0, Math.min(n - 1, ty)));
    if (!(t instanceof Float32Array)) return t === null ? 0 : NaN; // none: as the sea
    const px = Math.min(255, Math.floor((x - tx) * 256)), py = Math.min(255, Math.floor((y - ty) * 256));
    return t[py * 256 + px] * view3d.exaggeration;
  }

  /** Metres from the camera to the view's middle. */
  function cameraDistance() {
    const fov = map.getVerticalFieldOfView() * Math.PI / 180;
    const px = container.clientHeight / 2 / Math.tan(fov / 2);
    const lat = map.getCenter().lat * Math.PI / 180;
    return px * 2 * Math.PI * EARTH_R * Math.cos(lat) / (512 * Math.pow(2, map.getZoom()));
  }

  /**
   * The most tilt, up to pitch, that keeps the camera clear of the ground with the middle at middle
   * metres. slack: half the clearance will do (a camera already there stays, no back and forth).
   */
  function clearPitch(pitch, middle, slack) {
    const c = map.getCenter(), b = map.getBearing() * Math.PI / 180, d = cameraDistance();
    const k = Math.PI / 180, cosLat = Math.cos(c.lat * k);
    // The ground h metres back from the middle (the camera looks along the bearing).
    const back = (h) => groundAt(c.lng - h * Math.sin(b) / (EARTH_R * cosLat) / k, c.lat - h * Math.cos(b) / EARTH_R / k);
    const clearance = Math.max(15, d * 0.05) * (slack ? 0.5 : 1);
    if (isNaN(middle)) return pitch;
    for (let p = pitch; p > 0; p -= 0.5) {
      const g = back(d * Math.sin(p * k));
      if (isNaN(g)) return p; // not known yet: settled when it is
      if (middle + d * Math.cos(p * k) >= g + clearance) return p;
    }
    return 0;
  }

  /** The tilt a drag of dy pixels asks for: the view's tilt from now on, as far as the ground lets it. */
  function tilt3d(dy) {
    view3d.pitch = Math.max(0, Math.min(MAX_PITCH_3D, map.getPitch() - dy * 0.4));
    return view3d.pitch;
  }

  /** During gestures and the relief's slider: the middle on the ground, the camera out of it, at once. */
  function keepClear() {
    if (!view3d || !map || !map.terrain) return;
    const now = map.getCenterElevation(), g = middleGround(), middle = isNaN(g) ? now : g;
    const pitch = map.getPitch(), p = clearPitch(pitch, middle, true);
    if (p < pitch - 0.25 || Math.abs(middle - now) > 1) map.jumpTo({ pitch: p, elevation: middle }, SETTLING);
  }

  /** The moves made here (their moveend doesn't settle again). */
  const SETTLING = { settling: true };

  /**
   * After a move, and as the elevation arrives: the middle onto the ground, and the view at the
   * tilt it was given as far as the ground lets it (back up when it was lowered for a hill).
   */
  function settle3d(e) {
    if (!view3d || view3d.leaving || !map || !map.terrain || pointers.size || map.isMoving()) return;
    if (e && e.settling) { keepClear(); return; }
    const now = map.getCenterElevation(), pitch = map.getPitch();
    const g = middleGround();
    const middle = isNaN(g) ? now : g;
    const p = clearPitch(view3d.pitch, middle);
    if (Math.abs(p - pitch) <= 0.25 && Math.abs(middle - now) <= 1) return;
    if (clearPitch(pitch, now, true) < pitch - 0.25) {
      map.jumpTo({ pitch: p, elevation: middle }, SETTLING); // in the ground now: out at once
    } else {
      // MapLibre's ease brings the middle onto the ground: the camera rises (or sinks) with it.
      map.easeTo({ pitch: p, duration: 500, easing: SMOOTH }, SETTLING);
    }
  }

  let settleTimer = null;
  /** Settles a moment later (elevation keeps arriving). */
  function settleSoon() {
    if (!view3d) return;
    if (settleTimer) clearTimeout(settleTimer);
    settleTimer = setTimeout(() => { settleTimer = null; settle3d(null); }, 150);
  }

  function onData3d(e) {
    if (view3d && e.sourceId === DEM3D && e.tile) settleSoon();
  }

  function remove3d() {
    for (const id of [SAT3D, SHADE3D, BUILDINGS3D]) if (map.getLayer(id)) map.removeLayer(id);
    map.setTerrain(null);
    for (const id of [SAT3D, SHADE3D, DEM3D]) if (map.getSource(id)) map.removeSource(id);
    map.setProjection({ type: 'mercator' });
  }

  // A right-button drag turns and tilts the 3D view: no browser menu there.
  window.addEventListener('contextmenu', (e) => {
    if (!view3d || !container) return;
    const r = container.getBoundingClientRect();
    if (e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom) e.preventDefault();
  });

  window.MapAroundMap = {
    /** Right to left: the map's own things on the other side. */
    setRtl(v) {
      rtl = !!v;
    },
    create(containerId, styleJson, symbols, onTile, onUserGesture) {
      container = document.getElementById(containerId);
      symbolBase = symbols;
      tileHandler = onTile;
      onGesture = onUserGesture;
      // One map at a time: the map screen makes a new one each time it's shown.
      if (map) {
        try { map.remove(); } catch (e) { /* already gone */ }
      }
      firstView = startView;
      startView = null;
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
      map.on('style.load', apply3d);
      map.on('moveend', writeHash);
      map.on('moveend', settle3d);
      map.on('data', onData3d);
    },
    setStyle(json) {
      map.setStyle(JSON.parse(json), { diff: false });
    },
    setGeoJson(id, json) {
      const data = JSON.parse(json);
      geo.set(id, data);
      if (id === PIN_SOURCE) writePin(data);
      const s = map.getSource(id);
      if (s && s.setData) s.setData(data);
    },
    camera() {
      const c = map.getCenter();
      return [c.lat, c.lng, map.getZoom(), map.getBearing()].join(',');
    },
    height() { return container ? container.clientHeight : 0; },
    move(lat, lon, zoom, animated) {
      if (firstView) {
        // The app places the new map where it was last; the address asked for another view.
        const v = firstView;
        firstView = null;
        map.jumpTo(v);
        return;
      }
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

    // ---- navigation: tilted, the way ahead up, our position low in the view ----
    /** anchor: where (lat, lon) is, from the top of the map (0) to the bottom (1). */
    navCamera(lat, lon, zoom, bearing, pitch, anchor, ms) {
      if (view3d || !map) return;
      const h = container ? container.clientHeight : 0;
      navigating = true;
      map.easeTo({
        center: [lon, lat], zoom: zoom, bearing: bearing, pitch: Math.min(pitch, MAX_PITCH_2D),
        // The middle of what's below the top padding is the point.
        padding: { top: Math.max(0, (2 * anchor - 1) * h), bottom: 0, left: 0, right: 0 },
        duration: ms, easing: (t) => t,
      });
    },
    /** Navigation's over: flat, north up, where it is. */
    endNav() {
      if (!navigating || !map) return;
      navigating = false;
      if (view3d) return;
      map.easeTo({ bearing: 0, pitch: 0, padding: { top: 0, bottom: 0, left: 0, right: 0 }, duration: 600, easing: SMOOTH });
    },

    // ---- the 3D view (pitch: MapLibre's, from straight down) ----
    /** intro: ease in from the 2D map (straight down) instead of jumping there. */
    enter3d(lat, lon, zoom, bearing, pitch, satellite, exaggeration, buildings, intro) {
      if (exit3dTimer) { clearTimeout(exit3dTimer); exit3dTimer = null; }
      const fresh = !view3d;
      if (!view3d) {
        if (navigating) { map.stop(); map.setPadding({ top: 0, bottom: 0, left: 0, right: 0 }); navigating = false; }
        const c = map.getCenter();
        view3d = { back: { center: [c.lng, c.lat], zoom: map.getZoom(), bearing: map.getBearing(), pitch: 0 } };
        map.setMaxPitch(MAX_PITCH_3D);
        container.style.background = SPACE;
      }
      Object.assign(view3d, { satellite: satellite, exaggeration: exaggeration, buildings: buildings, leaving: false });
      apply3d();
      const to = { center: [lon, lat], zoom: zoom, bearing: bearing, pitch: Math.min(pitch, MAX_PITCH_3D) };
      // The tilt to keep (settle3d). Rebuilt (satellite on or off) at the tilt it had: the one it was
      // given, not the one a hill lowered it to.
      if (fresh || view3d.pitch === undefined || Math.abs(to.pitch - map.getPitch()) > 0.5) view3d.pitch = to.pitch;
      if (fresh && intro) map.easeTo(Object.assign(to, { duration: 1100, easing: SMOOTH }));
      else map.jumpTo(to);
    },
    /** Closing: eases back to the 2D map's look at (lat, lon, MapLibre's zoom), then done(). */
    leave3d(lat, lon, zoom, done) {
      let called = false;
      const finish = () => { if (!called) { called = true; done(); } };
      if (!view3d || !isFinite(lat) || !isFinite(lon) || !isFinite(zoom)) { finish(); return; }
      view3d.leaving = true; // flat now: no tilting back
      map.easeTo({ center: [lon, lat], zoom: zoom, bearing: 0, pitch: 0, duration: 600, easing: SMOOTH });
      map.once('moveend', finish);
      setTimeout(finish, 800); // nothing to move: no moveend
    },
    /** Back to 2D at (lat, lon), MapLibre's zoom (NaN: where the 2D map was before). */
    exit3d(lat, lon, zoom) {
      // A moment later: the 3D view may be rebuilt straight away (satellite on or off).
      if (exit3dTimer) clearTimeout(exit3dTimer);
      exit3dTimer = setTimeout(() => {
        exit3dTimer = null;
        if (!view3d || !map) return;
        const back = isFinite(lat) && isFinite(lon) && isFinite(zoom)
          ? { center: [lon, lat], zoom: zoom, bearing: 0, pitch: 0 }
          : view3d.back;
        view3d = null;
        try { remove3d(); } catch (e) { /* a new style came meanwhile */ }
        map.setMaxPitch(MAX_PITCH_2D);
        container.style.background = '';
        map.jumpTo(back);
      }, 0);
    },
    exaggeration3d(e) {
      if (!view3d) return;
      view3d.exaggeration = e;
      if (map.getSource(DEM3D)) map.setTerrain({ source: DEM3D, exaggeration: e });
      keepClear(); // higher hills: the camera stays above them
      // The buildings' foundations and roofs follow the exaggerated ground.
      if (map.getLayer(BUILDINGS3D)) {
        map.setPaintProperty(BUILDINGS3D, 'fill-extrusion-height', buildingTop(e));
        map.setPaintProperty(BUILDINGS3D, 'fill-extrusion-base', buildingBase(e));
      }
    },
    buildings3d(on) {
      if (!view3d || view3d.buildings === on) return;
      view3d.buildings = on;
      apply3d();
    },
    /** "lat,lon,zoom,bearing,pitch,height of the view". */
    camera3d() {
      const c = map.getCenter();
      return [c.lat, c.lng, map.getZoom(), map.getBearing(), map.getPitch(), container ? container.clientHeight : 0].join(',');
    },
    loading3d() { return !!view3d && !map.areTilesLoaded(); },
    hasBuildings3d() { return !!map.getSource(OSM_SOURCE); },
    /** The zoom slider: to MapLibre's zoom [z] at once (the camera's kept out of the ground after). */
    zoom3d(z) {
      if (view3d) map.jumpTo({ zoom: z });
    },
    /** A search's result: the 3D view eases over to it. */
    lookAt3d(lat, lon) {
      if (view3d) map.easeTo({ center: [lon, lat], duration: 900, easing: SMOOTH });
    },
    north3d() {
      if (view3d) view3d.pitch = 45;
      map.easeTo({ bearing: 0, pitch: 45, duration: 600, easing: SMOOTH });
    },

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
    /** The points of interest drawn within radius CSS px of (lat, lon), as JSON (see NativeMap.kt). */
    poisNear(lat, lon, radius) {
      const style = map && map.getStyle();
      if (!style || !style.layers) return '[]';
      const layers = style.layers.filter((l) => l.type === 'symbol' && l['source-layer'] === 'poi').map((l) => l.id);
      if (!layers.length) return '[]';
      const c = map.project([lon, lat]);
      const fs = map.queryRenderedFeatures([[c.x - radius, c.y - radius], [c.x + radius, c.y + radius]], { layers: layers });
      return JSON.stringify(fs.filter((f) => f.geometry && f.geometry.type === 'Point')
        .map((f) => ({ lat: f.geometry.coordinates[1], lon: f.geometry.coordinates[0], p: f.properties })));
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
