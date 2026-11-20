// Pruebas del tema claro/oscuro sin navegador ni dependencias: `npm test` (node --test).
// Se simulan window, localStorage y document; src/theme.ts se carga tal cual (Node quita los tipos).
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import { applyTheme, initialTheme, saveTheme } from '../src/theme.ts'

const KEY = 'gymflow-panel:theme'
const TOKEN_KEY = 'gymflow-panel:server-token'

function memoryStorage(initial = {}) {
  const data = new Map(Object.entries(initial))
  return {
    getItem: (k) => (data.has(k) ? data.get(k) : null),
    setItem: (k, v) => data.set(k, String(v)),
    removeItem: (k) => data.delete(k),
    data,
  }
}
const brokenStorage = { getItem() { throw new Error('bloqueado') }, setItem() { throw new Error('bloqueado') } }

function fakeDocument() {
  const meta = { content: '#F7F9FC', setAttribute(name, v) { if (name === 'content') this.content = v } }
  return { documentElement: { dataset: {} }, querySelector: (sel) => (sel === 'meta[name="theme-color"]' ? meta : null), meta }
}

/** Ejecuta public/theme-init.js (el script que corre antes de pintar) y devuelve el tema aplicado. */
function runInit({ search = '', storage }) {
  const document = fakeDocument()
  const window = { location: { search }, localStorage: storage }
  vm.runInNewContext(readFileSync(new URL('../public/theme-init.js', import.meta.url), 'utf8'), { window, document, URLSearchParams })
  return document.documentElement.dataset.theme
}

function useGlobals({ search = '', storage }) {
  globalThis.window = { location: { search } }
  globalThis.localStorage = storage
  globalThis.document = fakeDocument()
}

test('theme-init.js: claro si no hay nada guardado (no sigue al sistema)', () => {
  assert.equal(runInit({ storage: memoryStorage() }), 'light')
})

test('theme-init.js: aplica el oscuro guardado antes de pintar', () => {
  assert.equal(runInit({ storage: memoryStorage({ [KEY]: 'dark' }) }), 'dark')
})

test('theme-init.js: ?theme= manda sobre lo guardado', () => {
  assert.equal(runInit({ search: '?theme=light', storage: memoryStorage({ [KEY]: 'dark' }) }), 'light')
  assert.equal(runInit({ search: '?theme=dark', storage: memoryStorage() }), 'dark')
})

test('theme-init.js: valores raros o almacenamiento bloqueado → claro', () => {
  assert.equal(runInit({ storage: memoryStorage({ [KEY]: '<script>' }) }), 'light')
  assert.equal(runInit({ storage: brokenStorage }), 'light')
})

test('initialTheme: mismas reglas que theme-init.js', () => {
  useGlobals({ storage: memoryStorage() })
  assert.equal(initialTheme(), 'light')
  useGlobals({ storage: memoryStorage({ [KEY]: 'dark' }) })
  assert.equal(initialTheme(), 'dark')
  useGlobals({ search: '?demo&theme=light', storage: memoryStorage({ [KEY]: 'dark' }) })
  assert.equal(initialTheme(), 'light')
  useGlobals({ storage: brokenStorage })
  assert.equal(initialTheme(), 'light')
})

test('saveTheme: se recuerda al recargar y no toca el token del panel', () => {
  const storage = memoryStorage({ [TOKEN_KEY]: 'gfp_prueba' })
  useGlobals({ storage })
  saveTheme('dark')
  assert.equal(storage.getItem(KEY), 'dark')
  assert.equal(storage.getItem(TOKEN_KEY), 'gfp_prueba')
  assert.equal(runInit({ storage }), 'dark') // "recarga"
  saveTheme('light')
  assert.equal(runInit({ storage }), 'light')
})

test('saveTheme: sin almacenamiento no rompe la página', () => {
  useGlobals({ storage: brokenStorage })
  assert.doesNotThrow(() => saveTheme('dark'))
})

test('applyTheme: marca el documento y el color de la barra del navegador', () => {
  useGlobals({ storage: memoryStorage() })
  applyTheme('dark')
  assert.equal(globalThis.document.documentElement.dataset.theme, 'dark')
  assert.equal(globalThis.document.meta.content, '#101827')
  applyTheme('light')
  assert.equal(globalThis.document.documentElement.dataset.theme, 'light')
  assert.equal(globalThis.document.meta.content, '#F7F9FC')
})
