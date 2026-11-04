# INFORME DE CORRECCIONES — Ronda 4 (tras la tercera auditoría de Codex sobre 64e8ec4)

**Rama local:** `feature/import-sync-multidevice` · **HEAD auditado:** `64e8ec400769572eb5f22995a0b08a411d0a1df3`
**Estado:** **CORRECCIONES PARCIALES — BLOQUEO PENDIENTE (R3-04).** R3-01, R3-02, R3-03, M-03 y la importación heredada están implementados; **R3-04 no se ha resuelto** porque exige el esquema histórico auténtico (ver §3). No se declara aprobado nada ni se alcanza «CORRECCIONES IMPLEMENTADAS» completo.

Restricciones respetadas: sin push, merge, PR, release, despliegue, instalación en móviles, Firebase de producción ni datos reales. Commits anteriores intactos. Datos de prueba sintéticos.

## 1. Preparación
Informe de Codex leído íntegro (aportado en el mensaje). Rama `feature/import-sync-multidevice`, HEAD `64e8ec4`, árbol limpio antes de empezar.

## 2. Hallazgos

### R3-01 — Guarda CRLF (corregido, verificado)
- **Causa:** `indexOf("\n\n")` devuelve −1 en CRLF.
- **Solución (solo test):** el lector normaliza a LF y la extracción usa `untilBlankLine` con regex `\r?\n[ \t]*\r?\n`. Se mantienen todas las aserciones. Nuevas pruebas: extracción idéntica con LF y CRLF, la guarda sigue detectando la dependencia del servidor con ambos finales, y el lector entrega siempre LF. Producción no se tocó.
- **Verificado:** `Round3GuardTest` 9/9 y `LegacyImportGuardTest` 5/5 con fuentes LF **y** con una copia CRLF de `app/src` (simulación de checkout Windows) — ver §5.

### R3-02 — Fixture de identidad (corregido, NO ejecutado)
`RoomStoresTest.importIsAtomicAndNeverOverwrites` pasa ahora `ownerIdentity = st.boundIdentity` en sus dos planes; aserciones intactas; `requireSame` de producción sin cambios. Requiere emulador: pendiente de ejecución.

### R3-03 — Cancelación de Task.await (corregido; lógica verificada en JVM; Task real NO ejecutado)
- **Causa:** solo había listeners de éxito y fallo.
- **Solución:** `TaskAwait.kt` delega en `awaitCallbacks` (syncCore, Kotlin puro) y registra `addOnSuccessListener`, `addOnFailureListener` y **`addOnCanceledListener`**. Solo cuenta el primer desenlace (`AtomicBoolean`); Task cancelado ⇒ `CancellationException` inmediata (antes o durante la espera); corrutina cancelada ⇒ deja de esperar y el resultado tardío se ignora. Sin timeouts. Comportamiento documentado en el KDoc.
- **Pruebas JVM (Round4Test, 7 casos):** (1) éxito, (2) excepción (tipo y mensaje; kotlinx puede recrear la instancia al recuperar la traza), (3) cancelado antes de esperar, (4) cancelado mientras suspendido, (5) cancelación del llamador, (6) carrera éxito/cancelación ×300 en dos hilos, (7) resultado tardío tras cancelar.
- **Prueba con Tasks reales** (`TaskAwaitTest`, androidTest, 5 casos con `TaskCompletionSource`/`CancellationTokenSource`): escrita, **no ejecutada**. Las pruebas JVM modelan los callbacks, no el SDK de Google.

### R3-04 — Esquemas Room 3.json y 4.json (NO RESUELTO — se solicita el archivo)
No dispongo del `3.json` auténtico del commit histórico `1a17b49` (nunca se versionó y no puedo compilar Android aquí) ni puedo generar `4.json` (lo emite KSP al compilar). Fabricarlos a mano violaría el requisito. Lo hecho:
- `app/schemas/README.md`: documenta origen e identidad esperada (`3`: `32a6e958badc613142b6b8920ec7611a`, `4`: `d7996e1a59d6e18ca49dae537a03cc80`, según la auditoría) y el procedimiento de obtención.
- `SchemaFilesGuardTest`: 1.json y 2.json deben existir; 3 y 4 se **saltan** (2 skipped, explícito) mientras no estén y verifican su `identityHash` cuando se incorporen.

**Solicito:** los archivos `3.json` (generado del build de `1a17b49`) y `4.json` (del build de esta rama) que Codex conserva en `auditoria-import-sync-64e8ec4-evidencias`. Con ellos los añado tal cual, verifico los hashes y confirmo que el `build.gradle` los empaqueta como assets de `androidTest`. Hasta entonces `MigrationTest` no es reproducible desde un checkout limpio.

### M-03 — DataEvents y recargas (defecto real encontrado y corregido)
Revisión: `DataEvents` es un `SharedFlow` sin replay (el ViewModel de `GymFlowViewModel` se suscribe en su `init`, de modo que los eventos emitidos mientras vive no se pierden; si el proceso se recrea, `loadAll()` en el estado autenticado recarga desde Room). **Defecto reproducible por análisis y modelado:** cada `reload*`/`load*` publicaba su resultado sin comprobar que (a) seguía siendo la recarga más reciente ni (b) la cuenta activa seguía siendo la misma: una recarga lenta de la cuenta A podía repoblar las listas tras cambiar a B o cerrar sesión, y dos recargas solapadas podían terminar fuera de orden.
- **Corrección acotada:** `ReloadGate` (syncCore): solo la última recarga iniciada y de la cuenta activa publica; `clearData()` invalida todas. Aplicada a las seis listas. Los avisos seguidos ya se funden (`DROP_OLDEST`), sin bucles nuevos.
- **Pruebas JVM:** recarga antigua que termina tarde no publica; A→B y cierre de sesión no publican; `invalidate`; escenario completo A lenta + cambio a B.
- **Limitación:** no se ha hecho prueba visual/UI real (diálogos abiertos durante importación/resolución, recreación autenticada). M-03 **no** se declara cerrado visualmente.

### Importación Firestore heredada
Documentada como **incremental, no globalmente atómica**. Cambios: nueva `LegacyImportPolicy`/`runLegacyImport` (syncCore) reemplaza los filtros globales `has*`: distingue **ausente / ya importado por la misma cuenta / colisión con otra cuenta o catálogo global**; una colisión nunca se sobrescribe ni cuenta como importada y se informa en la UI; un fallo entre agregados conserva lo escrito, no avanza más y el reintento continúa sin duplicar; solo se marca «hecho» si terminó sin fallos. `GymRepository.legacyOwner` resuelve el propietario real. Pruebas JVM sintéticas (4): fallo entre agregados + reintento sin duplicados, colisión con otra cuenta, decisión por propietario, cambio de cuenta a mitad. **No** se conectó Firebase; el flujo con el SDK de Firestore sigue sin ejecutarse.

## 3. Archivos modificados
`syncCore`: `CallbackAwait.kt`, `ReloadGate.kt`, `LegacyImport.kt` (nuevos). `app`: `TaskAwait.kt`, `FirestoreImporter.kt`, `GymRepository.kt`, `GymFlowViewModel.kt`, `schemas/README.md` (nuevo). Tests: `Round4Test.kt`, `LegacyImportTest.kt`, `Round3GuardTest.kt`, `SchemaFilesGuardTest.kt` (nuevo), `RoomStoresTest.kt`, `TaskAwaitTest.kt` (nuevo, androidTest).

## 4. Pruebas ejecutadas / pendientes

| Verificación | Resultado |
|---|---|
| `syncCore` | **106 tests, 0 fallos** (91 previos + 15 nuevos) |
| Servidor | **51 tests, 0 fallos** (repetido en esta ronda; código sin cambios) |
| `Round3GuardTest` + `LegacyImportGuardTest` + `SchemaFilesGuardTest` (harness JVM, fuentes LF) | 17 tests: 15 pasan, 0 fallos, **2 omitidos** (3.json/4.json ausentes) |
| Las mismas guardas sobre una copia CRLF de `app/src` | 14/14 pasan (SchemaFilesGuardTest no incluida) |
| Type-check con stubs de `sync/*`, `TaskAwait`, `ImportSyncViewModel`, núcleo | OK |
| `:app:compileDebugKotlin`, JVM de `app` completa, `assembleDebug`, `assembleDebugAndroidTest` | **NO ejecutados** (sin SDK/Google Maven). Codex Windows los compiló en la ronda anterior; **el código nuevo de `FirestoreImporter`, `GymRepository` y `GymFlowViewModel` no ha sido compilado por nadie** |
| `MigrationTest`, `RoomStoresTest`, `GoogleSignInRequestTest`, `TaskAwaitTest` | **NO ejecutados** |

## 5. Riesgos pendientes
1. R3-04 abierto (esquemas 3/4) ⇒ migraciones no reproducibles desde checkout limpio; M-01 sigue abierto.
2. Código Android nuevo sin compilar (`FirestoreImporter` reescrito en su FASE 2, `legacyOwner`, `gates` en el ViewModel).
3. `TaskAwait` verificado sobre su lógica, no sobre Tasks reales hasta ejecutar `TaskAwaitTest`.
4. M-03 sin prueba UI; algunas publicaciones de mensajes de `ImportSyncViewModel` (`resolve`, `saveExternalCopy`) siguen sin comprobar `current()`.
5. Importación heredada incremental (por diseño); escenario Firestore real no probado.
6. Memoria de descarga acotada por nº de cambios, no por bytes (sin cambios).

## 6. Commits nuevos (sobre 64e8ec4)
Ver `HEAD.txt` y `git log 64e8ec4..HEAD` en el bundle: 1 commit de código + 1 de informe.

## 7. Instrucciones para la cuarta auditoría
1. `git bundle verify` y SHA-256. 2. Incorporar `3.json`/`4.json` auténticos (§R3-04), verificar hashes con `SchemaFilesGuardTest` (debe pasar sin omitidos). 3. Compilar `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (en Windows con CRLF), `assembleDebug`, `assembleDebugAndroidTest`. 4. En AVD desechable: `MigrationTest`, `RoomStoresTest`, `GoogleSignInRequestTest`, `TaskAwaitTest`. 5. Prueba manual de M-03 con sesión sintética local: importar JSON / resolver conflicto con pantallas de rutinas, historial y estadísticas abiertas, y cambio de cuenta durante una recarga.
