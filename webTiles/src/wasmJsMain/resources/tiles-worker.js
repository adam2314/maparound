// MapAround web: the tile worker. Keeps the downloaded maps in the browser's file storage (OPFS,
// folder "maps"), reads them synchronously (only workers may), and answers the map's tile
// requests with the shared Kotlin tile code (maptiles.js). Messages from the page:
//   { type: 'tile', id, path }  →  { type: 'tile', id, data: ArrayBuffer | null }
//   { type: 'import', id, file } →  { type: 'progress', id, done, total }…, { type: 'imported', id, name | error }
//   { type: 'delete', name }
//   { type: 'dem', name, data: ArrayBuffer | null }  (an elevation file for contours and hill shading; null: gone)
// and after every change the maps: { type: 'maps', maps: [{ name, size, mtime, header }] }
// (header: the start of the file up to the end of mapsforge's header, for the page's screens).
'use strict';

const handles = new Map(); // name → FileSystemSyncAccessHandle
let kotlin = null;
let ready = false;
const waiting = [];
let dir = null;

// Kotlin reads a map through this (synchronously).
self.__mrRead = (name, at, n) => {
  const h = handles.get(name);
  if (!h) return new Uint8Array(0);
  const b = new Uint8Array(n);
  const r = h.read(b, { at: at });
  return r < n ? b.subarray(0, r) : b;
};

async function mapsDir() {
  if (!dir) dir = await (await navigator.storage.getDirectory()).getDirectoryHandle('maps', { create: true });
  return dir;
}

/** mapsforge's header: magic, header size (bytes 20–23), version, file size (bytes 28–35). */
function headerInfo(b) {
  const magic = 'mapsforge binary OSM';
  if (b.length < 36) return null;
  for (let i = 0; i < magic.length; i++) if (b[i] !== magic.charCodeAt(i)) return null;
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  return { headerLength: 24 + v.getInt32(20), fileSize: Number(v.getBigInt64(28)) };
}

/** Opens every map again, tells Kotlin, and sends the list to the page. */
async function openAll() {
  for (const h of handles.values()) {
    try { h.close(); } catch (e) { /* already closed */ }
  }
  handles.clear();
  const d = await mapsDir();
  const list = [];
  for await (const [name, fh] of d.entries()) {
    if (fh.kind !== 'file' || !name.endsWith('.map')) continue;
    let file, h;
    try {
      file = await fh.getFile();
      h = await fh.createSyncAccessHandle();
    } catch (e) {
      continue;
    }
    const size = h.getSize();
    let head = new Uint8Array(Math.min(size, 65536));
    h.read(head, { at: 0 });
    const info = headerInfo(head);
    if (!info || info.fileSize !== size) {
      // Not a map, or one whose import was cut short.
      h.close();
      await d.removeEntry(name).catch(() => {});
      continue;
    }
    if (info.headerLength > head.length) {
      head = new Uint8Array(info.headerLength);
      h.read(head, { at: 0 });
    }
    handles.set(name, h);
    list.push({ name: name, size: size, mtime: file.lastModified, header: head.slice(0, info.headerLength) });
  }
  if (kotlin) kotlin.setMaps(list.map((m) => m.name + '\t' + m.size).join('\n'));
  self.postMessage({ type: 'maps', maps: list }, list.map((m) => m.header.buffer));
}

/** A picked .map file into the storage (in pieces, so big maps don't need their size in memory). */
async function importFile(id, file) {
  try {
    const name = file.name.replace(/ \(\d+\)(?=\.map$)/i, '').replace(/[^A-Za-z0-9._-]/g, '_').replace(/(\.map)?$/i, '.map');
    const info = headerInfo(new Uint8Array(await file.slice(0, 64).arrayBuffer()));
    if (!info) throw new Error('Not a mapsforge .map file');
    if (info.fileSize !== file.size) throw new Error('The file is incomplete (still downloading?)');
    const d = await mapsDir();
    const old = handles.get(name);
    if (old) {
      old.close();
      handles.delete(name);
    }
    const fh = await d.getFileHandle(name, { create: true });
    const h = await fh.createSyncAccessHandle();
    try {
      h.truncate(0);
      let at = 0;
      while (at < file.size) {
        const chunk = new Uint8Array(await file.slice(at, at + (8 << 20)).arrayBuffer());
        h.write(chunk, { at: at });
        at += chunk.length;
        self.postMessage({ type: 'progress', id: id, done: at, total: file.size });
      }
      h.flush();
    } finally {
      h.close();
    }
    await openAll();
    self.postMessage({ type: 'imported', id: id, name: name });
  } catch (e) {
    const msg = e && e.name === 'QuotaExceededError' ? 'Not enough storage for this map in the browser' : String((e && e.message) || e);
    self.postMessage({ type: 'imported', id: id, error: msg });
    await openAll().catch(() => {});
  }
}

async function remove(name) {
  const h = handles.get(name);
  if (h) {
    h.close();
    handles.delete(name);
  }
  await (await mapsDir()).removeEntry(name).catch(() => {});
  await openAll();
}

function handle(m) {
  switch (m.type) {
    case 'tile': {
      let bytes = null;
      try { bytes = kotlin.tile(m.path); } catch (e) { bytes = null; }
      if (bytes) self.postMessage({ type: 'tile', id: m.id, data: bytes.buffer }, [bytes.buffer]);
      else self.postMessage({ type: 'tile', id: m.id, data: null });
      break;
    }
    case 'import': importFile(m.id, m.file); break;
    case 'delete': remove(m.name); break;
    case 'dem':
      try { kotlin.setDem(m.name, m.data ? new Uint8Array(m.data) : null); } catch (e) { console.warn('MapAround: elevation file', m.name, e); }
      break;
    case 'list': openAll(); break;
  }
}

self.onmessage = (e) => {
  if (ready) handle(e.data);
  else waiting.push(e.data);
};

async function bytes(url) {
  try {
    const r = await fetch(url);
    return r.ok ? new Uint8Array(await r.arrayBuffer()) : null;
  } catch (e) {
    return null;
  }
}

// maptiles.js calls this when Kotlin is ready: the world map, the stored maps, then the requests.
self.__mrReady = async () => {
  kotlin = self.__mrKotlin;
  kotlin.init(await bytes('../world.bin'), await bytes('../borders.bin'), await bytes('../regions.bin'));
  try {
    await openAll();
  } catch (e) {
    self.postMessage({ type: 'maps', maps: [], error: String((e && e.message) || e) });
  }
  ready = true;
  self.postMessage({ type: 'ready' });
  for (const m of waiting.splice(0)) handle(m);
};

importScripts('maptiles.js');
