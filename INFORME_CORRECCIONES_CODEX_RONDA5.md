# INFORME DE CORRECCIONES — Ronda 5 (tras la cuarta auditoría de Codex sobre 875d23a)

**Rama local:** `feature/import-sync-multidevice` · **HEAD auditado:** `875d23a0a5d5b38636d531ddc6198759086dc385`
**Estado:** **CORRECCIONES IMPLEMENTADAS — PENDIENTE DE QUINTA AUDITORÍA CODEX.** Android sigue sin poder compilarse en Linux: lo que depende de Android queda pendiente (§5). No se declara aprobado nada.

Restricciones respetadas: sin push, merge, PR, release, despliegue, instalación en móviles ni Firebase de producción. Commits anteriores intactos; `3.json`/`4.json` sin modificar (SHA-256 verificados en el clon final). Datos sintéticos.

## 1. Correcciones

### R4-01 — Guarda Room y CRLF (corregido, verificado en Linux con checkouts LF y CRLF)
- **Causa:** `sha()` hasheaba los bytes del checkout; Git en Windows (`core.autocrlf=true`) los entrega en CRLF.
- **Solución (dos capas, sin tocar los hashes auténticos):**
  1. `.gitattributes` nuevo: `app/schemas/**/*.json text eol=lf` → los esquemas se extraen siempre en LF.
  2. `RoomSchemaFilesTest.sha()` normaliza CRLF→LF antes de hashear (robusto frente a zip/copias); la suma esperada sigue siendo la **auténtica** (`6c67f708…` y `a7ed3adc…`), nunca una suma CRLF. Se conservan identityHash, 14 entidades, diferencia 3→4 y migración sobre SQLite real. La prueba no se omite.
  3. Pruebas nuevas: `.gitattributes` contiene la regla; la normalización produce la suma auténtica a partir de una versión CRLF y el bruto CRLF difiere.
- **Verificación (clones reales de Git):** (a) `git clone -c core.autocrlf=true`: `git ls-files --eol` muestra los esquemas `w/lf` (atributo `text eol=lf`) mientras el resto de fuentes están `w/crlf`; SHA-256 de 3.json/4.json auténticos; **syncCore completo 128/128**. (b) `core.autocrlf=false`: 128/128. (c) Esquemas forzados a CRLF en un clon: `RoomSchemaFilesTest` 5/5. (d) Guardas JVM de `app` sobre las fuentes CRLF del clon: 19/19, 0 omitidos.

### R4-02 — Cambio de cuenta durante la importación heredada (corregido en el núcleo; cableado Android sin compilar)
- **Causa:** `stillActive` se comprobaba antes de `ownerOf` pero no después ni antes de `write`; los logros y la marca final no se revalidaban; el envoltorio capturaba `CancellationException`.
- **Solución (`runLegacyImport`):** identidad revalidada (1) antes de `ownerOf`, (2) **después** de `ownerOf`, (3) **dentro de la transacción** (`atomically`) justo antes de persistir, junto con una **relectura del propietario** (si otra cuenta reclamó el id entre medias → `LegacyOwnershipChangedException`, sin escribir), y (4) antes de `onComplete`. `CancellationException` se propaga siempre (también en `ownerOf`, `write`, `onComplete`). Los **logros** pasan a ser agregados de la misma política (id estable con uid) y la **marca de «hecho»** vive solo en `onComplete`. En Android `atomically = repo.inTransaction` (`db.withTransaction`; las `save*` internas se unen a ella, y si el bloque lanza todo se revierte). `FirestoreImporter` ya no captura `CancellationException` (tampoco en `ensureCatalog`). Consentimiento explícito, incrementalidad e idempotencia intactos.
- **Pruebas (`LegacyImportIdentityTest`, 9; todas pasan):** reproducción literal de Codex (cambio dentro de `ownerOf` ⇒ 0 escrituras), cambio entre decisión y transacción (con rollback), propietario reclamado entre consulta y escritura, cambio durante los logros, cambio justo antes de marcar completado, `onComplete` solo una vez si todo sigue activo, cancelación en `ownerOf`/`write`/`onComplete` propagada, rollback ante cancelación en `write`, colisión ajena. Un test previo basado en contar comprobaciones se reformuló para no depender de cuántas hace el helper (misma propiedad: nada se escribe tras el cambio).

### R4-03 — Inicio/Progreso no actualizaban contenido (corregido; UI sin ejecutar)
- **Causa:** `remember(history.size)` (y `customExercises.size`) no cambia al sustituir un entreno existente.
- **Solución:** `HistoryFingerprint.of(history)` (FNV‑1a de 64 bits sobre id, usuario, nombre, fecha, duración, ejercicios y todas las series; no usa `String.hashCode`) como clave de `remember` en `HomeTab`, `ProgressTab`, `MuscleMapCard` (ahora recibe `contentKey`), `PRBoardScreen` y `ExerciseProgressScreen`; `customExercises.toList()` para el catálogo. Se recalcula al leer la lista, así que detecta también el patrón `clear()+addAll()` directo que usó el fixture de Codex y no depende de re-emitir `DataEvents`. Además `ListPublisher` (sobre `ReloadGate`) solo reemplaza la lista si el contenido recargado difiere (sin recargas redundantes) y mantiene la política de cuenta/última-recarga; `clearData()` invalida y resetea los flags de «cargando» (observación de Codex §6).
- **Pruebas ejecutadas:** `HistoryFingerprintTest` (10, sobre el `Models.kt`/`HistoryFingerprint.kt` reales en un harness JVM): mismo nº de entrenos con distinto peso/fecha/nombre/ejercicio/serie/rpe/estado, cambio de cuenta (misma cardinalidad), mutación en el sitio, patrón reload `clear+add` con sustituto del mismo id, colisión `"Aa"/"BB"`, orden. `ListPublisherTest` (9, syncCore): igual cardinalidad con cambio de contenido/peso/fecha/nombre, mismo contenido no sube revisión, cambio de cuenta durante la recarga no publica, `clear` invalida las recargas en vuelo, **dos recargas solapadas** (la antigua que termina tarde se descarta). Guardas estáticas (`Round3GuardTest` +2): ninguna pantalla cachea con `size`; el importador revalida y propaga cancelación.
- **Pruebas Compose (`HomeProgressReloadTest`, androidTest, 3 casos):** reproducen el fixture de Codex (sustituto del mismo id y mismo tamaño en Inicio), cambio de nombre/fecha en Inicio y de peso en Progreso. **Escritas, NO ejecutadas** (requieren emulador). El cambio de cuenta y las recargas solapadas **no** tienen prueba Compose (no hay sesión Firebase local): se cubren en JVM.

## 2. Archivos modificados
`.gitattributes` (nuevo); `syncCore`: `LegacyImport.kt`, `ListPublisher.kt` (nuevo); `app`: `HistoryFingerprint.kt` (nuevo), `ProgressScreens.kt`, `MuscleMapScreen.kt`, `ExerciseProgressScreen.kt`, `PRBoardScreen.kt`, `GymFlowViewModel.kt`, `FirestoreImporter.kt`, `GymRepository.kt`. Tests: `RoomSchemaFilesTest.kt`, `LegacyImportTest.kt`, `ListPublisherTest.kt`, `HistoryFingerprintTest.kt`, `Round3GuardTest.kt`, `HomeProgressReloadTest.kt` (androidTest).

## 3. Resultados exactos (ejecutados en Linux)
| Verificación | Resultado |
|---|---|
| `syncCore` completo (árbol de trabajo) | **128 tests, 0 fallos** |
| `syncCore` completo en clon `core.autocrlf=true` (fuentes CRLF, esquemas LF por `.gitattributes`) | **128/128** |
| `syncCore` completo en clon `core.autocrlf=false` | **128/128** |
| `RoomSchemaFilesTest` con esquemas forzados a CRLF | 5/5 |
| Servidor completo (PostgreSQL embebido) | **51/51** |
| Guardas JVM de `app` (Round3 11, LegacyImport 5, SchemaFiles 3; harness aislado, fuentes CRLF del clon) | **19/19, 0 omitidos** |
| `HistoryFingerprintTest` (harness con `Models.kt` real) | **10/10** |
| Pruebas nuevas de identidad (`LegacyImportIdentityTest`) | 9/9 |
| Pruebas nuevas de caché/recarga (`ListPublisherTest`) | 9/9 |

## 4. Regresiones comprobadas (por suites existentes, todas verdes)
Importación JSON sin servidor y aislamiento de cuentas (`CodexRound3Test`, `CodexCorrectionsTest`), importación heredada con consentimiento (guardas), sincronización offline-first y resolución de conflictos (`SyncScenarioTest`), migraciones (`RoomMigrationSqlTest` + `RoomSchemaFilesTest` sobre SQLite real), servidor 51/51. **Google Sign-In v3, migraciones Room 1→4 en Room real, historial/rutinas/estadísticas en pantalla: no ejecutadas** (requieren Android).

## 5. Pendiente en Codex Windows (no ejecutado aquí)
`:app:compileDebugKotlin` (código nuevo/modificado: `FirestoreImporter`, `GymRepository.inTransaction/legacyOwner`, `GymFlowViewModel` con `ListPublisher`, pantallas con `HistoryFingerprint`), `:app:testDebugUnitTest` completo, `assembleDebug`/`assembleDebugAndroidTest`, `MigrationTest`, `RoomStoresTest`, `GoogleSignInRequestTest`, `TaskAwaitTest`, **`HomeProgressReloadTest`** (nuevo) y, si se desea cerrar M-03, recreación de Activity autenticada.

## 6. Estado de M-03
**Parcialmente corregido, no cerrado.** El defecto reproducido (claves por `size`) está corregido en código y la lógica cubierta en JVM, pero la verificación en pantalla (Compose real) está sin ejecutar. Siguen fuera de verificación: flujo autenticado, recreación de Activity, diálogos de conflicto y las seis pantallas con sesión local. El listener anónimo de `GymFlowViewModel` (:123) sigue sin retirarse (observación de Codex; el ViewModel vive con la Activity).

## 7. Riesgos residuales
1. Código Android nuevo sin compilar. 2. La huella cuesta O(series) por composición (aceptable; no medido en dispositivo). 3. La relectura transaccional del propietario depende de que `db.withTransaction` anide las `save*` (comportamiento documentado de Room; sin prueba instrumentada). 4. La importación heredada sigue siendo incremental (no atómica global) y, con colisiones omitidas, se marca completa: el usuario ve el aviso pero no se ofrece reintento automático. 5. `HomeProgressReloadTest` puede necesitar ajustes de textos/semántica al ejecutarse (no se pudo probar). 6. Sin cambio en: memoria de descarga por nº de cambios, A→B→A entre comprobaciones.

## 8. Commits nuevos (sobre 875d23a) y HEAD
`f6002bb` (R4-01/02/03) · `f802dd7` (autoprueba CRLF) · commit de este informe. HEAD completo: ver `HEAD.txt` / `SHA256SUMS.txt`.

## 9. Instrucciones para la quinta auditoría
1. `git bundle verify` y SHA-256. 2. Checkout Windows nuevo con `core.autocrlf=true`: `git ls-files --eol app/schemas/...` debe mostrar `w/lf`; `syncCore` completo y `:app:testDebugUnitTest` sin excluir guardas. 3. Compilar y ejecutar en AVD desechable `HomeProgressReloadTest` (reproduce tu fixture sin modificar producción), `MigrationTest`, `RoomStoresTest`, `GoogleSignInRequestTest`, `TaskAwaitTest`. 4. Repetir tu fixture de cambio de cuenta dentro de `ownerOf` (ahora 0 escrituras) y uno con cambio durante los logros.
