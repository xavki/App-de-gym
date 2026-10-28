// ─── Tema claro / oscuro ──────────────────────────────────────────────────────
// Claro por defecto. La elección del usuario se recuerda en este navegador (es una
// preferencia visual, no un dato). No se sigue el tema del sistema operativo.
// public/theme-init.js aplica lo mismo antes de pintar para que no haya destellos.

export type Theme = 'light' | 'dark'

const THEME_KEY = 'gymflow-panel:theme'
const THEME_COLOR: Record<Theme, string> = { light: '#F7F9FC', dark: '#101827' }

/** ?theme=dark|light manda (enlaces y pruebas); si no, lo guardado; si no, claro. */
export function initialTheme(): Theme {
  const param = new URLSearchParams(window.location.search).get('theme')
  if (param === 'light' || param === 'dark') return param
  try { return localStorage.getItem(THEME_KEY) === 'dark' ? 'dark' : 'light' } catch { return 'light' }
}

export function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLOR[theme])
}

export function saveTheme(theme: Theme) {
  try { localStorage.setItem(THEME_KEY, theme) } catch { /* sin almacenamiento: no se recuerda */ }
}
