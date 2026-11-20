import type { IconName } from './icons'

// ─── Navegación ───────────────────────────────────────────────────────────────
// Separado de App.tsx para poder probarlo sin navegador (tests/navigation.test.mjs).

/** Vistas; son también los valores de ?tab= (los enlaces antiguos siguen valiendo). */
export type Tab = 'overview' | 'workouts' | 'routines' | 'exercises' | 'body' | 'nutrition'
export const TAB_KEYS: Tab[] = ['overview', 'workouts', 'routines', 'exercises', 'body', 'nutrition']

/** De dónde salen los datos que se ven. */
export type Source = 'file' | 'demo' | 'server'

/** Las cinco secciones de la navegación. Estadísticas agrupa Ejercicios y Cuerpo. */
export interface Section { key: string; label: string; short: string; icon: IconName; tabs: Tab[] }
export const SECTIONS: Section[] = [
  { key: 'overview', label: 'Resumen', short: 'Resumen', icon: 'home', tabs: ['overview'] },
  { key: 'workouts', label: 'Entrenamientos', short: 'Entrenos', icon: 'dumbbell', tabs: ['workouts'] },
  { key: 'routines', label: 'Rutinas', short: 'Rutinas', icon: 'clipboard', tabs: ['routines'] },
  { key: 'stats', label: 'Estadísticas', short: 'Estadísticas', icon: 'chart', tabs: ['exercises', 'body'] },
  { key: 'nutrition', label: 'Nutrición', short: 'Nutrición', icon: 'apple', tabs: ['nutrition'] }, // solo vinculado al servidor
]
export const STATS_TABS: { key: Tab; label: string }[] = [{ key: 'exercises', label: 'Ejercicios' }, { key: 'body', label: 'Cuerpo' }]
export const SUBTITLE: Record<Tab, string> = {
  overview: 'Tu actividad de un vistazo',
  workouts: 'Cada sesión con sus ejercicios y series',
  routines: 'Tus plantillas de entrenamiento',
  exercises: 'Progresión y récords por ejercicio',
  body: 'Peso corporal y medidas',
  nutrition: 'Comidas del plan, macros y objetivos',
}

/** ?tab=… → vista; cualquier otro valor (o ninguno) abre el Resumen. */
export const tabFromParam = (param: string | null): Tab => TAB_KEYS.find((t) => t === param) ?? 'overview'

/** Sección de la navegación a la que pertenece una vista. */
export const sectionOf = (tab: Tab): Section => SECTIONS.find((s) => s.tabs.includes(tab)) ?? SECTIONS[0]

/** Nutrición solo aparece con el panel vinculado al servidor (lee y escribe en la API). */
export const visibleSections = (source: Source): Section[] =>
  source === 'server' ? SECTIONS : SECTIONS.filter((s) => s.key !== 'nutrition')

/** Las vistas que dependen del rango de fechas (Rutinas y Nutrición no). */
export const usesRange = (tab: Tab) => tab !== 'nutrition' && tab !== 'routines'
