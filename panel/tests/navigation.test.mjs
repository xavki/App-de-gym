// Pruebas de la navegación (src/navigation.ts): `npm test` (node --test), sin dependencias.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { SECTIONS, STATS_TABS, TAB_KEYS, sectionOf, tabFromParam, usesRange, visibleSections } from '../src/navigation.ts'

test('los enlaces ?tab= de antes siguen abriendo su vista', () => {
  for (const tab of ['overview', 'exercises', 'body', 'workouts', 'nutrition']) assert.equal(tabFromParam(tab), tab)
  assert.equal(tabFromParam('routines'), 'routines')
})

test('?tab= desconocido o ausente abre el Resumen', () => {
  for (const p of [null, '', 'stats', 'NUTRITION', '<script>']) assert.equal(tabFromParam(p), 'overview')
})

test('cinco secciones en este orden; Estadísticas agrupa Ejercicios y Cuerpo', () => {
  assert.deepEqual(SECTIONS.map((s) => s.label), ['Resumen', 'Entrenamientos', 'Rutinas', 'Estadísticas', 'Nutrición'])
  assert.equal(sectionOf('exercises').key, 'stats')
  assert.equal(sectionOf('body').key, 'stats')
  assert.deepEqual(STATS_TABS.map((t) => t.key), ['exercises', 'body'])
})

test('cada vista pertenece a una sola sección y cada sección abre una vista válida', () => {
  for (const tab of TAB_KEYS) assert.equal(SECTIONS.filter((s) => s.tabs.includes(tab)).length, 1, tab)
  for (const s of SECTIONS) assert.ok(TAB_KEYS.includes(s.tabs[0]), s.key)
})

test('Nutrición solo se muestra con el panel vinculado al servidor', () => {
  assert.ok(visibleSections('server').some((s) => s.key === 'nutrition'))
  for (const source of ['file', 'demo']) {
    const keys = visibleSections(source).map((s) => s.key)
    assert.ok(!keys.includes('nutrition'), source)
    assert.equal(keys.length, 4)
  }
})

test('el filtro de fechas no aparece en Rutinas ni en Nutrición', () => {
  assert.equal(usesRange('routines'), false)
  assert.equal(usesRange('nutrition'), false)
  for (const t of ['overview', 'workouts', 'exercises', 'body']) assert.equal(usesRange(t), true, t)
})
