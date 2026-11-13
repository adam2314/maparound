// The tile code runs in a Web Worker: no window or document (webpack's "web" target reads
// document.baseURI to find the .wasm).
config.target = 'webworker';
config.output = config.output || {};
config.output.globalObject = 'self';

// The shared code depends on Compose, whose graphics runtime (skiko) the tile code never uses. The
// generated loader imports its module all the same; in the production build (unused code removed)
// the .wasm needs nothing from it, so an empty module stands in. Build this worker in production.
const path = require('path');
const fs = require('fs');
const webpack = require('webpack');
const stub = path.resolve(__dirname, 'skiko-stub.mjs');
fs.writeFileSync(stub, 'export {};\n');
config.plugins = config.plugins || [];
config.plugins.push(new webpack.NormalModuleReplacementPlugin(/skiko\.mjs$/, stub));
