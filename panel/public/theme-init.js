// Aplica el tema guardado antes de pintar la página (evita el destello del tema equivocado).
// Va en un archivo aparte porque la CSP del servidor no permite scripts en línea.
// Claro por defecto: no se sigue el tema del sistema operativo. Ver src/theme.ts.
(function () {
  var theme = 'light'
  try {
    var param = new URLSearchParams(window.location.search).get('theme')
    var saved = window.localStorage.getItem('gymflow-panel:theme')
    var chosen = param === 'dark' || param === 'light' ? param : saved
    if (chosen === 'dark') theme = 'dark'
  } catch { /* sin almacenamiento: claro */ }
  document.documentElement.dataset.theme = theme
})()
