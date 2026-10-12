const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const htmlPath = path.join(__dirname, '..', 'galaxy-screen-preview.html');
const html = fs.readFileSync(htmlPath, 'utf8');
const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/i);
assert(scriptMatch, 'The preview must include its self-contained script.');

const context = vm.createContext({ URLSearchParams });
vm.runInContext(scriptMatch[1], context, { filename: htmlPath, timeout: 1000 });
const model = context.GalaxyPreviewModel;
assert(model, 'The preview model must be available to the local tests.');

let passed = 0;
function test(name, run) {
  run();
  passed += 1;
  console.log(`✓ ${name}`);
}

const equal = (actual, expected, message) => assert.equal(actual, expected, message);
const near = (actual, expected, epsilon = 1e-8) => assert.ok(Math.abs(actual - expected) <= epsilon,
  `expected ${actual} to be within ${epsilon} of ${expected}`);

// S1 — initial content, center model and expected UI shell.
test('rendersInitialScene', () => {
  const layout = model.buildLayout(model.DEFAULT_FOLDERS, 320, 520);
  equal(layout.nodes.length, 6);
  equal(layout.ringRadii.length, 1);
  assert(Number.isFinite(layout.fitScale) && layout.fitScale > 0 && layout.fitScale <= 1);
  for (const id of ['sun-anchor', 'orbit-map', 'controls', 'bottom-navigation']) {
    assert(html.includes(`id="${id}"`), `missing #${id}`);
  }
  assert.match(html, /aria-current="page"[^>]*>/);
});

// S2 — a focused/opened node still resolves to the correct folder identity.
test('focusThenOpenUsesCorrectFolder', () => {
  const layout = model.buildLayout(model.DEFAULT_FOLDERS, 320, 520);
  const target = layout.nodes.find((node) => node.id === '4');
  equal(target.name, 'المشاريع');
  assert.match(scriptMatch[1], /function handleFolderPress\(id\)/);
  assert.match(scriptMatch[1], /if \(camera\.scale < LABEL_SCALE\) focusFolder\(id\);\s*else openFolder\(id\);/);
});

// S3 — list search returns the exact folder and uses its ID when opening it.
test('searchOpensExpectedFolder', () => {
  const results = model.filterFolders(model.DEFAULT_FOLDERS, 'الاختبارات');
  equal(results.length, 1);
  equal(results[0].id, '3');
  assert.match(scriptMatch[1], /button\.addEventListener\("click", \(\) => openFolder\(folder\.id\)\)/);
});

// S4 — zoom is reversible around an anchor, and overview bounds remain finite.
test('cameraZoomAndResetStayBounded', () => {
  const start = { scale: 0.75, panX: 14, panY: -6 };
  const zoomed = model.zoomCamera(start, 1.5, 0.5, model.MAX_ZOOM, 200, 260, 160, 250);
  const restored = model.zoomCamera(zoomed, 1 / 1.5, 0.5, model.MAX_ZOOM, 200, 260, 160, 250);
  near(restored.scale, start.scale);
  near(restored.panX, start.panX);
  near(restored.panY, start.panY);
  const maxed = model.zoomCamera(start, 1000, 0.5, model.MAX_ZOOM, 160, 250, 160, 250);
  equal(maxed.scale, model.MAX_ZOOM);
});

// S5 — small and resized viewports retain a usable fit scale.
test('smallViewportKeepsControlsVisible', () => {
  const small = model.buildLayout(model.DEFAULT_FOLDERS, 280, 420);
  assert(Number.isFinite(small.fitScale) && small.fitScale > 0 && small.fitScale <= 1);
  assert.match(html, /new ResizeObserver\(/);
  assert.match(html, /window\.addEventListener\("resize", updateViewport/);
  assert.match(html, /@media \(max-width: 360px\)/);
});

// S6 — Arabic labels remain text, and planet sizes stay within the app's bounds.
test('folderSizingAndRtlLabelSafe', () => {
  equal(model.planetSize(0), 28);
  assert(model.planetSize(10000) <= 40);
  const [folder] = model.normalizeFolders([{ id: 9, name: 'عنوان عربي طويل جدًا', fileCount: 0 }]);
  equal(folder.name, 'عنوان عربي طويل جدًا');
  const compact = model.compactLabel(folder.name, 10);
  assert(compact.length <= 10 && compact.endsWith('…'));
  equal(folder.name, 'عنوان عربي طويل جدًا');
  assert.match(scriptMatch[1], /name\.textContent = compactLabel\(node\.name\)/);
  assert.match(html, /<html lang="ar" dir="rtl">/);
});

// S7 — a no-match query does not mutate the folder source.
test('emptySearchShowsNoMatchesWithoutMutation', () => {
  const source = model.DEFAULT_FOLDERS.map((folder) => ({ ...folder }));
  const before = JSON.stringify(source);
  const results = model.filterFolders(source, 'اسم غير موجود');
  equal(results.length, 0);
  equal(JSON.stringify(source), before);
  assert.match(html, /لا توجد مجلدات مطابقة/);
});

// S8 — empty and error modes are distinct; retry returns to the demo state.
test('loadingEmptyAndErrorStatesStayDistinct', () => {
  equal(model.stateFromSearch('?state=loading'), 'loading');
  equal(model.stateFromSearch('?state=empty'), 'empty');
  equal(model.stateFromSearch('?state=error'), 'error');
  equal(model.stateFromSearch('?state=unknown'), 'ready');
  assert.match(html, /id="loading-state"[\s\S]*?جارٍ تحميل المجلدات/);
  assert.match(html, /لا توجد مجلدات بعد/);
  assert.match(html, /تعذّر تحميل مجلدات المجرة/);
  assert.match(scriptMatch[1], /retry-button[\s\S]*?setMode\("ready"\)/);
});

// S9 — hostile input is treated as plain text, never as injected markup.
test('searchInputIsRenderedAsText', () => {
  const hostile = '"><img src=x onerror=alert(1)>';
  equal(model.filterFolders(model.DEFAULT_FOLDERS, hostile).length, 0);
  assert.match(scriptMatch[1], /name\.textContent = folder\.name/);
  assert.doesNotMatch(scriptMatch[1], /innerHTML\s*=\s*[^;\n]*folder\./);
});

// S10 — repeated or invalid camera inputs cannot create an infinite/oversized zoom.
test('rapidZoomAndMotionToggleRemainStable', () => {
  let camera = { scale: 0.8, panX: 0, panY: 0 };
  for (let index = 0; index < 100; index += 1) {
    camera = model.zoomCamera(camera, 1.5, 0.5, model.MAX_ZOOM, 160, 250, 160, 250);
  }
  assert(Number.isFinite(camera.scale));
  equal(camera.scale, model.MAX_ZOOM);
  const unchanged = model.zoomCamera(camera, Infinity, 0.5, model.MAX_ZOOM, 160, 250, 160, 250);
  assert(Number.isFinite(unchanged.scale));
  equal(unchanged.scale, model.MAX_ZOOM);
  assert.match(html, /prefers-reduced-motion: reduce/);
  assert.match(scriptMatch[1], /motionEnabled = !mediaReduceMotion\.matches/);
});

console.log(`\nAll ${passed} Galaxy preview checks passed.`);
