#!/usr/bin/env node
/**
 * Guard for the web control panel.
 *
 * WebUi.kt builds an HTML+JS page inside Kotlin raw strings, which means the
 * Kotlin compiler never sees the JavaScript. A quoting slip there (e.g. writing
 * \\' where \' is needed) ships a page whose <script> block fails to parse at
 * runtime, so the whole panel silently renders blank. This script reconstructs
 * the page exactly as the app would and fails the build if it is not valid.
 *
 * Usage: node scripts/check-webui.js [path/to/WebUi.kt]
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');
/** ------------------------------------------------------ */
const file = process.argv[2] || path.join(
  __dirname, '..', 'app', 'src', 'main', 'java', 'com', 'cwbridge', 'android', 'WebUi.kt');

if (!fs.existsSync(file)) {
  console.error('check-webui: cannot find ' + file);
  process.exit(1);
}

const src = fs.readFileSync(file, 'utf8');
const problems = [];

// Kotlin raw strings do not process escapes, so the body is taken verbatim.
// trimIndent() only strips a COMMON indent; these parts start at column 0, so
// the content is preserved as-is.
const partRe = /private val PART_[A-Z]: String = """\r?\n([\s\S]*?)"""/g;
const parts = [];
let m;
while ((m = partRe.exec(src)) !== null) parts.push(m[1]);

if (parts.length === 0) {
  console.error('check-webui: found no PART_* raw strings in ' + file);
  console.error('  If the page was restructured, update this script to match.');
  process.exit(1);
}
const page = parts.join('\n');

// The screenshot button is conditionally injected via a placeholder comment.
// If the constant and the comment drift apart, the button is silently dropped
// (or never appears) with no error anywhere.
const markerConst = src.match(/SCREENSHOT_MARKER\s*=\s*"([^"]*)"/);
if (markerConst) {
  const marker = markerConst[1];
  if (!page.includes(marker)) {
    problems.push('SCREENSHOT_MARKER (' + marker + ') does not appear in the page markup');
  }
}

// Collect inline script blocks.
const scripts = [];
const scriptRe = /<script>([\s\S]*?)<\/script>/g;
let s;
while ((s = scriptRe.exec(page)) !== null) scripts.push(s[1]);

if (scripts.length === 0) {
  problems.push('no <script> block found in the generated page');
} else {
  const js = scripts.join('\n');
  try {
    // Compiles without executing, exactly like `node --check`.
    new vm.Script(js, { filename: 'webui-inline.js' });
  } catch (e) {
    problems.push('inline JavaScript does not parse: ' + e.message);
  }
  // A stray double backslash before a quote is the specific mistake that made
  // the panel render blank once; flag it explicitly for a clearer message.
  const stray = (js.match(/\\\\'/g) || []).length;
  if (stray > 0) {
    problems.push(
      'found ' + stray + ' occurrence(s) of \\\\  in inline JS. ' +
      'Kotlin raw strings do NOT process escapes, so write \\\' (one backslash) ' +
      'to escape a quote inside JS.');
  }
}

// Coarse structural sanity: the page must open and close the tags it opens.
for (const tag of ['html', 'head', 'body']) {
  const open = (page.match(new RegExp('<' + tag + '[ >]', 'gi')) || []).length;
  const close = (page.match(new RegExp('</' + tag + '>', 'gi')) || []).length;
  if (open !== 1 || close !== 1) {
    problems.push('expected exactly one <' + tag + '> and </' + tag + '>, found ' + open + '/' + close);
  }
}

if (problems.length) {
  console.error('check-webui: FAILED for ' + file);
  for (const p of problems) console.error('  - ' + p);
  process.exit(1);
}

console.log('check-webui: OK (' + parts.length + ' parts, ' + scripts.length +
  ' script block(s), ' + page.length + ' bytes)');
