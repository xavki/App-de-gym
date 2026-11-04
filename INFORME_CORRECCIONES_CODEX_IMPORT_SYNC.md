# INFORME DE CORRECCIONES — Auditoría independiente Codex (import/sync multidispositivo)

**Rama local:** `feature/import-sync-multidevice` · **HEAD auditado por Codex:** `1a17b49` · **Base:** `895f1fb`
**Estado:** **CORRECCIONES PARCIALES — BLOQUEOS PENDIENTES** (la capa Android no se ha podido compilar ni ejecutar aquí; ver §6 y §7).
No se declara el proyecto aprobado. Pendiente de segunda auditoría de Codex.

Restricciones respetadas: sin push, PR, merge, tags, despliegues, instalaciones en móviles, datos personales ni cambios en Firebase de producción. Los commits `cfe876e`, `50e605d` y `1a17b49` no se han modificado (sin amend/rebase): solo se añaden 3 commits nuevos y este informe. Todos los datos de prueba son sintéticos.

## 1. Resumen

| Hallazgo | Corrección en el núcleo (ejecutada) | Corrección en Android (NO compilada/ejecutada aquí) |
|---|---|---|
| H1 identidad | Sí, probada | Sí, solo comprobada con stubs de tipos |
| H2 aislamiento SQL | Sí (OwnerGuard + fixture), probada | Sí (SQL real), sin ejecutar |
| H3 resolver conserva edición local | Sí, probada | UI de segunda confirmación, sin ejecutar |
| H4 agregados/paginación | Sí, probada | n/a (lo hace el núcleo) |
| H5 tipos | Sí (SqlValues), probada | RoomRecordAccess usa SqlValues, sin ejecutar |
| H6 Firestore | n/a | Sí; guardas estáticas escritas, sin ejecutar |

## 2. Causa raíz y solución por hallazgo

### H1 — Operación no atada a la identidad
- **Causa:** el plan de importación/incorporación y los almacenes no llevaban la cuenta (ni el servidor) con la que se prepararon; al confirmar se usaba la sesión de ese momento.
- **Solución:** `Identified`/`requireSame` (`Safety.kt`): identidad `uid|serverKey` capturada al preparar (en el plan) y revalidada contra la identidad **viva** antes, dentro y justo antes de confirmar cada transacción, y entre cada llamada de red y su aplicación (`SyncRunner`, `Onboarding`, `ImportExecutor`, `ConflictResolver`). Android: `RoomSyncStore/RoomImportStore` reciben la identidad viva; `SyncManager.api(ctx, uid)` está atado a la cuenta y descarta el token si la sesión cambia mientras se pide; `ImportSyncViewModel` descarta toda vista previa/incorporación pendiente al cambiar el usuario de Firebase.
- **Resultado ante cambio:** `IdentityChangedException` / `SyncOutcome.Failed(IDENTITY)`; no se escribe nada, ni siquiera el último error.

### H2 — Aislamiento entre usuarios
- **Causa:** `INSERT OR REPLACE` por `id` podía sustituir una fila de otra cuenta con el mismo UUID; no se validaba el padre ni que `data.id` coincidiera.
- **Solución:** `OwnerGuard.checkWrite/checkDelete` (tabla conocida, UUID, `data.id == id`, propietario existente ∈ {ausente, huérfano, yo}, padre presente y mío). Android: propietario resuelto **por SQL** (cadena de padres), `INSERT` con `CONFLICT_ABORT` si no existe y `UPDATE` si ya es mío; nunca `REPLACE`. `sync_revs` pasa a clave `(userId, serverKey, tableName, id)`. Servidor: `data.id` debe igualar el id del registro.

### H3 — Resolver con servidor perdía la última edición local
- **Causa:** se archivaba el estado local del momento de detectar el conflicto, no el actual.
- **Solución:** `useRemote` archiva el agregado local **actual** completo antes de sustituir; si hubo ediciones/filas nuevas posteriores al conflicto lanza `LocalChangedSinceConflictException` y exige `acknowledgeLocalEdits = true` (2.ª confirmación en UI) y además archiva la versión «del momento del conflicto». Todo en una transacción (un fallo a mitad no pierde nada). `ArchiveRecovery` expone filas locales/remotas archivadas.

### H4 — Agregados y paginación
- **Causa:** la descarga se aplicaba página a página y un agregado podía mezclarse entre páginas; se subían solo filas sucias.
- **Solución:** `SyncRunner` reescrito: sube **agregados completos** (grupo atómico) con su `baseRev`; descarga **todas** las páginas sin aplicar y aplica en **una** transacción con el cursor; si una fila local sucia del agregado difiere de lo remoto, el agregado entero se congela como conflicto; segunda pasada desde 0 para completar los conflictos nuevos; agregados que no caben en una petición atómica (1900 filas) se omiten y se cuentan (`oversized`). El parser rechaza registros de >1500 filas.
- **Cambio de comportamiento documentado:** un corte a mitad de descarga ya no deja avance parcial (antes conservaba el cursor); el test antiguo se adaptó a la semántica atómica (no se debilitó: ahora exige 0 filas y cursor intacto).

### H5 — Tipos
- **Causa:** al pasar JSON→SQLite se convertía cualquier cadena `"true"/"false"` en booleano y los números se coercionaban.
- **Solución:** `SqlValues.toSql/fromSql` estrictos (booleanos solo en `exercises.isCustom` y `workout_sets.completed`; texto es texto; enteros exactos de 64 bits; sin NaN/Inf). `RoomRecordAccess` los usa en ambos sentidos. Parser: `units` ≠ kg/cm/s invalida el archivo; fechas opcionales no textuales invalidan el registro.

### H6 — Importación Firestore antes del consentimiento
- **Causa:** el listener de autenticación llamaba a `FirestoreImporter.importUserDataIfNeeded` en cada login.
- **Solución:** eliminada la llamada automática. `importUserDataWithConsent` exige un `LegacyConsent` (solo se crea en la acción explícita de pantalla, con diálogo), comprueba que la cuenta siga activa, **lee todo primero** (Source.SERVER) y solo después guarda; ids estables + `has*` evitan duplicados con la sincronización. `LegacyImportGuardTest` fija por análisis estático que el arranque/login no importa y que solo hay un llamador.

## 3. Archivos modificados

Núcleo: `Safety.kt` (nuevo), `SyncRunner.kt`, `Onboarding.kt`, `ImportPlanner.kt`, `ExportParser.kt`.
Servidor: `Module.kt`. Android: `RoomRecordAccess.kt`, `RoomStores.kt`, `SyncManager.kt`, `SyncService.kt`, `ImportSyncViewModel.kt`, `GymFlowViewModel.kt`, `FirestoreImporter.kt`, `ImportScreen.kt`, `HomeScreen.kt`, `SyncScreen.kt`, `SyncEntities.kt`, `Migrations.kt`.
Pruebas: `CodexCorrectionsTest.kt` (nuevo), `Fixtures.kt`, `FakeServer.kt`, `SyncScenarioTest.kt`, `SyncRevisionTest.kt`, `LegacyImportGuardTest.kt` (nuevo), `RoomStoresTest.kt` (androidTest).

## 4. Pruebas añadidas

- `CodexCorrectionsTest` (24 pruebas, ejecutadas): H1 ×6 (importar con cambio de cuenta, plan de otra cuenta, incorporación, sync sin red, cambio durante la descarga, resolver), H2 ×4 (fila ajena con mismo id, padre ajeno, ids/tablas/`data.id`, descarga con fila ajena revierte todo), H3 ×4, H4 ×4 (agregado con cambios repartidos en páginas, hermanos concurrentes, corte a mitad de aplicación, agregado oversize), H5 ×3, parser ×3. El `MemStore` de pruebas aplica `OwnerGuard`, tiene identidad viva mutable y filas ajenas.
- Servidor: 2 pruebas (`data.id` coherente; tamaño en bytes UTF‑8).
- `LegacyImportGuardTest` (4, **no ejecutadas**), `RoomStoresTest` +2 (**no ejecutadas**).

## 5. Resultados exactos (ejecutados en este entorno)

| Verificación | Resultado |
|---|---|
| `syncCore` JVM (`gradle test`) | **80 tests, 0 fallos** (56 previos + 24 nuevos; 1 de los 56 adaptado, ver H4) |
| Servidor (Ktor + PostgreSQL embebido) | **51 tests, 0 fallos** (49 previos + 2 nuevos) |
| Type-check con stubs (`ktcheck`) de `sync/*`, `SyncEntities`, `ImportSyncViewModel`, núcleo | OK (rc=0). **Excluye** `GymFlowViewModel`, `ImportScreen`, `HomeScreen`, `SyncScreen`, `FirestoreImporter` y los tests Android |

Reproducción: C-03, A-01 y A-02 de Codex fallaban en el código previo (comprobado con una prueba temporal antes de corregir) y ahora tienen prueba de regresión que pasa.

## 6. Pruebas NO ejecutadas / lo que falta (no se declara superado)

- Compilación Android real, **120 pruebas JVM de `app`**, `assembleDebug` y APK de instrumentación: **no ejecutados** (sin SDK/Google Maven en este entorno). Codex los ejecutó en Windows; deben repetirse.
- Pruebas instrumentadas, emulador/AVD y migraciones Room: **no ejecutadas**. `MIGRATION_2_3` se modificó en el sitio (clave de `sync_revs` con `userId`; v3 no estaba publicada) y **el esquema `app/schemas/.../3.json` no está regenerado** (M‑01 de Codex sigue abierto).
- `LegacyImportGuardTest`, `RoomStoresTest` nuevos y UI de H3/H6: sin ejecutar ni ver en pantalla.
- M‑03 (recarga de listas por eventos tras importar/resolver): **no abordado**.
- Sin prueba end-to-end contra un Firebase real/emulador (prohibido tocar producción).

## 7. Riesgos residuales

1. Código Android sin compilar: pueden existir errores de compilación o de runtime en archivos fuera del type-check con stubs.
2. `MIGRATION_2_3` reescrita + `3.json` sin regenerar: cualquier instalación que ya hubiera creado `sync_revs` con la clave antigua fallaría la validación de Room (no hay despliegues, pero debe verificarse en emulador).
3. La descarga acumula todos los cambios en memoria antes de aplicarlos (tope 500 000 cambios); con historiales enormes es costoso pero atómico.
4. Agregados >1900 filas no se sincronizan (se avisa con `oversized`); el parser ya impide importarlos >1500.
5. La importación legacy de Firestore sigue leyendo con el SDK de Firestore bajo consentimiento; su comportamiento real con reglas de seguridad no se ha podido comprobar.
6. Un cambio en la conexión de Firebase (`FirebaseAuth`) muy rápido (A→B→A) durante una operación se detecta por la identidad viva al final, no en cada instante intermedio.

## 8. Commits nuevos

```
974d892 fix(android): H1-H3, H5, H6 en la capa Room/UI (sin compilar en este entorno)
5581428 fix(server,syncCore): data.id coherente, tamaño en bytes UTF-8, unidades, tope de filas por agregado y fechas tipadas
24c136c fix(syncCore): H1-H5 identidad, aislamiento, resolución con archivo, agregados atómicos y tipos estrictos
```
(más el commit de este informe; el HEAD final se indica en la entrega).

## 9. Evidencia para la segunda auditoría

1. `git diff 1a17b49..HEAD --stat` y revisar `Safety.kt`, `SyncRunner.kt`, `Onboarding.kt` (ConflictResolver), `RoomRecordAccess.kt`.
2. En Windows: `gradle :syncCore:test`, `:server:test`, `:app:testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`; regenerar `3.json`; ejecutar `RoomStoresTest` y pruebas de migración en AVD **desechable**.
3. Repetir los cinco fallos reproducidos por Codex y el hallazgo de Firestore contra este HEAD.
4. Revisar manualmente: flujo de login sin importación legacy, diálogo de «Importar datos antiguos», diálogo «Has editado esto después».
