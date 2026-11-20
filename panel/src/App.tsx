import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  UnauthorizedError, claimCode, clearLocal, clearToken, detectServer, fetchServerExport, loadLocal, loadToken,
  parseExport, sampleExport, saveLocal, saveToken,
} from './data'
import { Icon } from './icons'
import {
  STATS_TABS, SUBTITLE, sectionOf, tabFromParam, usesRange as tabUsesRange, visibleSections, type Source, type Tab,
} from './navigation'
import { RANGES, fmtDate, inRange, prepare, rangeBounds, type RangeKey } from './stats'
import { applyTheme, initialTheme, saveTheme, type Theme } from './theme'
import type { GymExport } from './types'
import { Skeleton } from './ui'
import { Body } from './views/Body'
import { Exercises } from './views/Exercises'
import { Nutrition } from './views/Nutrition'
import { Overview } from './views/Overview'
import { Routines } from './views/Routines'
import { Workouts } from './views/Workouts'

// URL: ?demo carga los datos de ejemplo; ?tab=exercises abre esa pestaña; ?theme=dark fuerza el tema
const params = new URLSearchParams(window.location.search)

function initial(): { data: GymExport | null; source: Source } {
  if (params.has('demo')) return { data: sampleExport(), source: 'demo' }
  if (loadToken()) return { data: null, source: 'server' } // se cargará del servidor al arrancar
  const saved = loadLocal()
  if (!saved) return { data: null, source: 'file' }
  try { return { data: parseExport(saved), source: 'file' } } catch { return { data: null, source: 'file' } }
}

export default function App() {
  const [{ data, source }, setState] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const [onServer, setOnServer] = useState(false)
  // Si hay un token guardado, se arranca ya "cargando" desde el servidor
  const [loading, setLoading] = useState(() => source === 'server')
  const [code, setCode] = useState('')
  const [tab, setTab] = useState<Tab>(() => tabFromParam(params.get('tab')))
  const [range, setRange] = useState<RangeKey>('90d')
  const [theme, setTheme] = useState<Theme>(initialTheme)
  const fileInput = useRef<HTMLInputElement>(null)

  useEffect(() => { applyTheme(theme) }, [theme])
  const toggleTheme = () => {
    const next = theme === 'dark' ? 'light' : 'dark'
    setTheme(next)
    saveTheme(next)
  }

  /** Quien llama pone loading = true antes (o arranca ya cargando). */
  const loadFromServer = useCallback((token: string) =>
    fetchServerExport(token)
      .then((d) => { setState({ data: d, source: 'server' }); setError(null) })
      .catch((e: Error) => {
        if (e instanceof UnauthorizedError) {
          clearToken()
          setState({ data: null, source: 'file' })
        }
        setError(e.message)
      })
      .finally(() => setLoading(false)), [])

  useEffect(() => {
    detectServer().then(setOnServer)
    const token = loadToken()
    if (token && !params.has('demo')) loadFromServer(token)
  }, [loadFromServer])

  const pair = async () => {
    setLoading(true)
    try {
      const token = await claimCode(code)
      saveToken(token)
      setCode('')
      await loadFromServer(token)
    } catch (e) {
      setError((e as Error).message)
      setLoading(false)
    }
  }

  const unpair = () => {
    clearToken()
    setState({ data: null, source: 'file' })
  }

  const refresh = () => {
    const t = loadToken()
    if (t) { setLoading(true); loadFromServer(t) }
  }

  const openFile = async (file: File) => {
    try {
      const text = await file.text()
      const parsed = parseExport(text)
      saveLocal(text)
      setState({ data: parsed, source: 'file' })
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  /** El servidor rechazó el token (p. ej. se revocó desde la app): vuelta a la pantalla de vincular. */
  const lostAccess = useCallback((message: string) => {
    clearToken()
    setState({ data: null, source: 'file' })
    setError(message)
  }, [])

  const sections = visibleSections(source)
  /** La pestaña queda en la URL (?tab=…): recargar o un acceso directo en el móvil vuelve a ella. */
  const selectTab = (key: Tab) => {
    setTab(key)
    window.scrollTo({ top: 0 })
    try {
      const url = new URL(window.location.href)
      url.searchParams.set('tab', key)
      window.history.replaceState(null, '', url)
    } catch { /* sin History API: solo no se recuerda */ }
  }
  const token = source === 'server' ? loadToken() : null
  const current = sectionOf(tab)

  const workouts = useMemo(() => (data ? prepare(data) : []), [data])
  const bounds = useMemo(() => (data ? rangeBounds(range, workouts, data.exportedAt) : null), [data, workouts, range])
  const inR = useMemo(() => (bounds ? workouts.filter((w) => inRange(w.date, bounds)) : []), [workouts, bounds])

  const isDark = theme === 'dark'
  const info = source === 'demo' ? 'Datos de ejemplo'
    : source === 'server' ? 'En vivo desde tu servidor'
    : data ? `Exportado el ${fmtDate(new Date(data.exportedAt))}` : ''
  const usesRange = tabUsesRange(tab)

  const themeButton = (
    <button className="icon-btn" onClick={toggleTheme} aria-label={isDark ? 'Cambiar a tema claro' : 'Cambiar a tema oscuro'} title={isDark ? 'Tema claro' : 'Tema oscuro'}>
      <Icon name={isDark ? 'sun' : 'moon'} />
    </button>
  )

  /** Acciones según el origen de los datos; en el móvil se muestran solo los iconos. */
  const actions = data && (
    <>
      {source === 'server' && (
        <>
          <button className="btn ghost" onClick={refresh} disabled={loading} title="Volver a cargar los datos del servidor" aria-label="Actualizar">
            <Icon name="refresh" size={18} className={loading ? 'spin' : undefined} />
            <span className="btn-label">{loading ? 'Actualizando…' : 'Actualizar'}</span>
          </button>
          <button className="btn ghost" onClick={unpair} title="Olvidar el acceso de este navegador" aria-label="Desvincular">
            <Icon name="unlink" size={18} /><span className="btn-label">Desvincular</span>
          </button>
        </>
      )}
      {source !== 'server' && (
        <button className="btn ghost" onClick={() => fileInput.current?.click()} title="Abrir otro archivo exportado" aria-label="Cambiar archivo">
          <Icon name="file" size={18} /><span className="btn-label">Cambiar archivo</span>
        </button>
      )}
      {source === 'file' && (
        <button className="btn ghost" onClick={() => { clearLocal(); setState({ data: null, source: 'file' }) }} title="Olvidar el archivo guardado en este navegador" aria-label="Olvidar">
          <Icon name="trash" size={18} /><span className="btn-label">Olvidar</span>
        </button>
      )}
    </>
  )

  const fileInputEl = (
    <input
      ref={fileInput} type="file" accept="application/json,.json" hidden
      onChange={(e) => { const f = e.target.files?.[0]; if (f) openFile(f); e.target.value = '' }}
    />
  )

  const brand = (
    <div className="brand">
      <span className="logo" aria-hidden><Icon name="dumbbell" size={18} /></span>
      <span>GymFlow</span>
    </div>
  )

  // ─── Sin datos: bienvenida (vincular, abrir archivo o ver la demo) ─────────
  if (!data) {
    return (
      <div className="welcome-page" onDragOver={(e) => e.preventDefault()}
        onDrop={(e) => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f) openFile(f) }}>
        <header className="welcome-bar">{brand}{themeButton}{fileInputEl}</header>
        <main className="welcome">
          <div className="welcome-hero">
            <span className="welcome-badge"><Icon name="sparkle" size={14} /> Panel web</span>
            <h1>Tus entrenos y tu nutrición, en grande</h1>
            <p className="muted">Progresión, récords, rutinas y comidas del día, cómodo en el móvil y en el ordenador.</p>
          </div>
          {error && <div className="error" role="alert">{error}</div>}
          {source === 'server' && loading ? (
            <div className="welcome-loading">
              <Skeleton rows={2} label="Cargando tus datos del servidor" />
              <p className="muted">Cargando tus datos del servidor…</p>
            </div>
          ) : (
            <>
              {onServer && (
                <form className="pair card" onSubmit={(e) => { e.preventDefault(); pair() }}>
                  <span className="pair-icon"><Icon name="link" size={22} /></span>
                  <strong>Vincular con la app</strong>
                  <span className="muted small">En la app: <strong>Perfil → Sincronización → Vincular panel web</strong></span>
                  <div className="pair-row">
                    <input
                      className="code-input" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())}
                      placeholder="ABCD-EFGH" aria-label="Código de vinculación" autoComplete="off" maxLength={12}
                    />
                    <button className="btn" type="submit" disabled={loading || code.replace(/[^A-Z0-9]/g, '').length < 8}>
                      {loading ? 'Vinculando…' : 'Vincular'}
                    </button>
                  </div>
                </form>
              )}
              <p className="muted welcome-note">
                {onServer ? 'O abre un archivo exportado: ' : 'En la app ve a '}
                <strong>Perfil → Exportar datos → JSON</strong>, pasa el archivo al ordenador y ábrelo aquí.
                El archivo se procesa en tu navegador, no se sube a ningún sitio.
              </p>
              <div className="dropzone" onClick={() => fileInput.current?.click()} role="button" tabIndex={0}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') fileInput.current?.click() }}>
                <span className="dropzone-icon"><Icon name="upload" size={24} /></span>
                <strong>Arrastra aquí el JSON</strong>
                <span className="muted">o haz clic para elegirlo</span>
              </div>
              <button className="btn ghost" onClick={() => { setState({ data: sampleExport(), source: 'demo' }); setError(null) }}>
                <Icon name="sparkle" size={18} /> Ver con datos de ejemplo
              </button>
            </>
          )}
        </main>
      </div>
    )
  }

  // ─── Con datos: navegación lateral (ordenador) o inferior (móvil) ─────────
  return (
    <div
      className="shell"
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f) openFile(f) }}
    >
      {fileInputEl}

      <aside className="sidebar">
        {brand}
        <nav className="side-nav" aria-label="Secciones">
          {sections.map((s) => (
            <button key={s.key} className={s === current ? 'nav-item active' : 'nav-item'} aria-current={s === current ? 'page' : undefined}
              onClick={() => selectTab(s.tabs[0])}>
              <Icon name={s.icon} /><span>{s.label}</span>
            </button>
          ))}
        </nav>
        <div className="side-foot">
          <span className="source-pill"><span className={`dot ${source}`} />{info}</span>
          <button className="nav-item" onClick={toggleTheme}>
            <Icon name={isDark ? 'sun' : 'moon'} /><span>{isDark ? 'Tema claro' : 'Tema oscuro'}</span>
          </button>
        </div>
      </aside>

      <header className="appbar">
        {brand}
        <div className="appbar-actions">{actions}{themeButton}</div>
      </header>

      <main className="main">
        <div className="page-head">
          <div className="page-title">
            <h1>{current.label}</h1>
            <p className="muted">
              <span className="page-sub">{SUBTITLE[tab]}</span>
              <span className="page-info"> · {info} · {data.workouts.length} entrenos</span>
            </p>
          </div>
          <div className="page-actions">{actions}</div>
        </div>

        {error && <div className="error" role="alert">{error}</div>}

        {(current.key === 'stats' || usesRange) && (
          <div className="toolbar">
            {current.key === 'stats' && (
              <div className="segmented sub-tabs" role="tablist" aria-label="Estadísticas">
                {STATS_TABS.map((t) => (
                  <button key={t.key} role="tab" aria-selected={tab === t.key} className={tab === t.key ? 'seg active' : 'seg'} onClick={() => selectTab(t.key)}>
                    {t.label}
                  </button>
                ))}
              </div>
            )}
            {usesRange && (
              <div className="segmented range" role="radiogroup" aria-label="Rango de fechas">
                {RANGES.map((r) => (
                  <button key={r.key} role="radio" aria-checked={range === r.key} className={range === r.key ? 'seg active' : 'seg'} onClick={() => setRange(r.key)}>
                    {r.label}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}

        <div className="view">
          {bounds && tab === 'overview' && (
            <Overview workouts={inR} range={bounds} routines={data.routines.length} nutrition={source === 'server'} onNavigate={selectTab} />
          )}
          {bounds && tab === 'exercises' && <Exercises all={workouts} range={bounds} />}
          {bounds && tab === 'body' && <Body measurements={data.bodyMeasurements} range={bounds} />}
          {tab === 'workouts' && <Workouts workouts={inR} />}
          {tab === 'routines' && <Routines routines={data.routines} />}
          {tab === 'nutrition' && (token
            ? <Nutrition token={token} onUnauthorized={lostAccess} />
            : <div className="empty big">La nutrición se guarda en tu servidor: vincula el panel con la app para usarla.</div>)}
        </div>
      </main>

      <nav className="bottom-nav" aria-label="Secciones">
        {sections.map((s) => (
          <button key={s.key} className={s === current ? 'bn-item active' : 'bn-item'} aria-current={s === current ? 'page' : undefined}
            onClick={() => selectTab(s.tabs[0])}>
            <span className="bn-icon"><Icon name={s.icon} size={22} /></span>
            <span className="bn-label">{s.short}</span>
          </button>
        ))}
      </nav>
    </div>
  )
}
