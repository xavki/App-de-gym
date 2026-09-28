import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  UnauthorizedError, claimCode, clearLocal, clearToken, detectServer, fetchServerExport, loadLocal, loadToken,
  parseExport, sampleExport, saveLocal, saveToken,
} from './data'
import { RANGES, fmtDate, inRange, prepare, rangeBounds, type RangeKey } from './stats'
import type { GymExport } from './types'
import { Body } from './views/Body'
import { Exercises } from './views/Exercises'
import { Overview } from './views/Overview'
import { Workouts } from './views/Workouts'

type Tab = 'overview' | 'exercises' | 'body' | 'workouts'
const TABS: { key: Tab; label: string }[] = [
  { key: 'overview', label: 'Resumen' },
  { key: 'exercises', label: 'Ejercicios' },
  { key: 'body', label: 'Cuerpo' },
  { key: 'workouts', label: 'Entrenos' },
]

/** De dónde salen los datos que se ven. */
type Source = 'file' | 'demo' | 'server'

// URL: ?demo carga los datos de ejemplo; ?tab=exercises abre esa pestaña
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
  const [tab, setTab] = useState<Tab>(() => TABS.find((t) => t.key === params.get('tab'))?.key ?? 'overview')
  const [range, setRange] = useState<RangeKey>('90d')
  const [theme, setTheme] = useState<'light' | 'dark' | null>(() => {
    const t = params.get('theme')
    return t === 'light' || t === 'dark' ? t : null
  })
  const fileInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (theme) document.documentElement.dataset.theme = theme
    else delete document.documentElement.dataset.theme
  }, [theme])

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

  const workouts = useMemo(() => (data ? prepare(data) : []), [data])
  const bounds = useMemo(() => (data ? rangeBounds(range, workouts, data.exportedAt) : null), [data, workouts, range])
  const inR = useMemo(() => (bounds ? workouts.filter((w) => inRange(w.date, bounds)) : []), [workouts, bounds])

  const isDark = theme ? theme === 'dark' : window.matchMedia?.('(prefers-color-scheme: dark)').matches
  const info = source === 'demo' ? 'Datos de ejemplo'
    : source === 'server' ? 'En vivo desde tu servidor'
    : data ? `Exportado el ${fmtDate(new Date(data.exportedAt))}` : ''

  return (
    <div
      className="app"
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f) openFile(f) }}
    >
      <header className="topbar">
        <div className="brand">
          <span className="logo" aria-hidden>◆</span>
          <span>GymFlow</span>
          <span className="muted">Panel</span>
        </div>
        <div className="topbar-actions">
          {data && <span className="muted small file-info">{info} · {data.workouts.length} entrenos</span>}
          {source === 'server' && data && (
            <>
              <button className="btn ghost" onClick={() => { const t = loadToken(); if (t) { setLoading(true); loadFromServer(t) } }} disabled={loading}>
                {loading ? 'Actualizando…' : 'Actualizar'}
              </button>
              <button className="btn ghost" onClick={unpair}>Desvincular</button>
            </>
          )}
          {source !== 'server' && data && <button className="btn ghost" onClick={() => fileInput.current?.click()}>Cambiar archivo</button>}
          {source === 'file' && data && (
            <button className="btn ghost" onClick={() => { clearLocal(); setState({ data: null, source: 'file' }) }}>Olvidar</button>
          )}
          <button className="btn ghost icon" onClick={() => setTheme(isDark ? 'light' : 'dark')} aria-label="Cambiar tema" title="Cambiar tema">
            {isDark ? '☀' : '☾'}
          </button>
        </div>
        <input
          ref={fileInput} type="file" accept="application/json,.json" hidden
          onChange={(e) => { const f = e.target.files?.[0]; if (f) openFile(f); e.target.value = '' }}
        />
      </header>

      {error && <div className="error" role="alert">{error}</div>}

      {!data ? (
        <main className="welcome">
          <h1>Tus entrenos, en grande</h1>
          {source === 'server' && loading ? (
            <p className="muted">Cargando tus datos del servidor…</p>
          ) : (
            <>
              {onServer && (
                <form className="pair" onSubmit={(e) => { e.preventDefault(); pair() }}>
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
              <p className="muted">
                {onServer ? 'O abre un archivo exportado: ' : 'En la app ve a '}
                <strong>Perfil → Exportar datos → JSON</strong>, pasa el archivo al ordenador y ábrelo aquí.
                El archivo se procesa en tu navegador, no se sube a ningún sitio.
              </p>
              <div className="dropzone" onClick={() => fileInput.current?.click()} role="button" tabIndex={0}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') fileInput.current?.click() }}>
                <strong>Arrastra aquí el JSON</strong>
                <span className="muted">o haz clic para elegirlo</span>
              </div>
              <button className="btn ghost" onClick={() => { setState({ data: sampleExport(), source: 'demo' }); setError(null) }}>
                Ver con datos de ejemplo
              </button>
            </>
          )}
        </main>
      ) : (
        <main className="content">
          <nav className="filters" aria-label="Filtros">
            <div className="tabs" role="tablist">
              {TABS.map((t) => (
                <button key={t.key} role="tab" aria-selected={tab === t.key} className={tab === t.key ? 'tab active' : 'tab'} onClick={() => setTab(t.key)}>
                  {t.label}
                </button>
              ))}
            </div>
            <div className="segmented" role="radiogroup" aria-label="Rango de fechas">
              {RANGES.map((r) => (
                <button key={r.key} role="radio" aria-checked={range === r.key} className={range === r.key ? 'seg active' : 'seg'} onClick={() => setRange(r.key)}>
                  {r.label}
                </button>
              ))}
            </div>
          </nav>

          {bounds && tab === 'overview' && <Overview workouts={inR} range={bounds} />}
          {bounds && tab === 'exercises' && <Exercises all={workouts} range={bounds} />}
          {bounds && tab === 'body' && <Body measurements={data.bodyMeasurements} range={bounds} />}
          {bounds && tab === 'workouts' && <Workouts workouts={inR} />}
        </main>
      )}
    </div>
  )
}
