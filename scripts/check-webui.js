#!/usr/bin/env node
/**
 * Guard for the web control panel.
 * Reconstructs WebUi page + LOGIN_SHELL and validates JS / markup contracts.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const javaRoot = path.join(__dirname, '..', 'app', 'src', 'main', 'java');

function findWebUi(dir, hits) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); }
  catch (e) { return hits; }
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) findWebUi(full, hits);
    else if (entry.name === 'WebUi.kt') hits.push(full);
  }
  return hits;
}

let file = process.argv[2];
if (file) {
  if (!fs.existsSync(file)) {
    console.error('check-webui: cannot find ' + file);
    process.exit(1);
  }
} else {
  const hits = findWebUi(javaRoot, []);
  if (hits.length === 0) {
    console.error('check-webui: found no WebUi.kt under ' + javaRoot);
    process.exit(1);
  }
  if (hits.length > 1) {
    console.error('check-webui: multiple WebUi.kt files');
    process.exit(1);
  }
  file = hits[0];
}

const src = fs.readFileSync(file, 'utf8');

function extractParts() {
  const parts = [];
  const re = /private\s+val\s+(PART_[A-Z])\s*:\s*String\s*=\s*"""([\s\S]*?)"""\.trimIndent\(\)/g;
  let m;
  while ((m = re.exec(src))) parts.push({ name: m[1], body: m[2] });
  return parts;
}

const parts = extractParts();
if (parts.length < 5) {
  console.error('check-webui: expected PART_* blocks, found ' + parts.length);
  process.exit(1);
}

const html = parts.map(p => p.body).join('');
const scripts = [];
const scriptRe = /<script>([\s\S]*?)<\/script>/gi;
let sm;
while ((sm = scriptRe.exec(html))) scripts.push(sm[1]);
if (scripts.length < 1) {
  console.error('check-webui: no <script> in dashboard');
  process.exit(1);
}

for (let i = 0; i < scripts.length; i++) {
  try {
    new vm.Script(scripts[i], { filename: 'WebUi-part-' + i + '.js' });
  } catch (e) {
    console.error('check-webui: dashboard script parse failed:', e.message);
    process.exit(1);
  }
}

if (/<script[^>]+src\s*=/i.test(html)) {
  console.error('check-webui: external <script src> not allowed');
  process.exit(1);
}

const joined = scripts.join('\n');
if (/\B\$\s*\(/.test(joined) || /\B\$\./.test(joined)) {
  console.error('check-webui: bare $ usage detected');
  process.exit(1);
}

const ids = new Set();
const idRe = /\bid\s*=\s*"([A-Za-z][\w-]*)"/g;
let im;
while ((im = idRe.exec(html))) ids.add(im[1]);
const dRe = /\bD\(\s*["']([A-Za-z][\w-]*)["']\s*\)/g;
const missing = [];
while ((im = dRe.exec(joined))) {
  if (!ids.has(im[1])) missing.push(im[1]);
}
if (missing.length) {
  console.error('check-webui: D(id) missing elements: ' + missing.slice(0, 20).join(', '));
  process.exit(1);
}

const onclickRe = /\bonclick\s*=\s*"([A-Za-z_][\w]*)\s*\(/g;
const fnNames = new Set();
const fnRe = /function\s+([A-Za-z_][\w]*)\s*\(|async\s+function\s+([A-Za-z_][\w]*)\s*\(/g;
while ((im = fnRe.exec(joined))) fnNames.add(im[1] || im[2]);
const missingFn = [];
while ((im = onclickRe.exec(html))) {
  if (!fnNames.has(im[1])) missingFn.push(im[1]);
}
if (missingFn.length) {
  console.error('check-webui: onclick targets missing: ' + missingFn.join(', '));
  process.exit(1);
}

const shellMatch = src.match(/private\s+val\s+LOGIN_SHELL\s*:\s*String\s*=\s*"""([\s\S]*?)"""\.trimIndent\(\)/);
if (!shellMatch) {
  console.error('check-webui: LOGIN_SHELL missing');
  process.exit(1);
}
const shell = shellMatch[1];
const shellScripts = [];
const ssr = /<script>([\s\S]*?)<\/script>/gi;
while ((sm = ssr.exec(shell))) shellScripts.push(sm[1]);
for (const sc of shellScripts) {
  try { new vm.Script(sc, { filename: 'LOGIN_SHELL.js' }); }
  catch (e) {
    console.error('check-webui: LOGIN_SHELL script parse failed:', e.message);
    process.exit(1);
  }
}
if (!/\/api\/login/.test(shell)) {
  console.error('check-webui: LOGIN_SHELL must post /api/login');
  process.exit(1);
}
if (/id="app"|Restart bridge|sec-console/.test(shell)) {
  console.error('check-webui: LOGIN_SHELL leaks dashboard markup');
  process.exit(1);
}

console.log(
  'check-webui: OK for ' + path.relative(path.join(__dirname, '..'), file) +
  ' (' + parts.length + ' parts, ' + scripts.length + ' script block(s), ' +
  Buffer.byteLength(html, 'utf8') + ' bytes)'
);
