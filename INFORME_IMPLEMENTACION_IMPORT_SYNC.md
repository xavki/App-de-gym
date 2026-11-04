# GymFlow 1.1 — Importación JSON y sincronización multidispositivo offline-first

**Estado: PARCIAL — PENDIENTE DE AUDITORÍA CODEX.** El núcleo (`syncCore`) y el servidor están implementados y verificados con pruebas
automáticas ejecutadas. La capa Android (Room v3, almacenes, WorkManager, pantallas Compose) está escrita pero **NO se ha podido compilar ni
ejecutar** en este entorno (Google Maven bloqueado: no hay AGP/androidx/SDK). No está lista para producción. Nada se ha publicado, desplegado
ni instalado.

- Repositorio: `xavki/App-de-gym` (clon local). Rama: `feature/import-sync-multidevice`. Base: `895f1fbecbf161e396edf46519a1b84f70852871`
  (Google Sign-In v3 + Room offline-first; `e9c69e8`/`integration/google-signin-v3` **no existen en este entorno**, no se usaron).
- Commits: ver sección 20. Ningún `push`, PR, merge, tag ni release.

## 1. Resumen ejecutivo
1. **Importación JSON** (Perfil → Importar datos): lee la copia de GymFlow (`schemaVersion` 1), la valida de forma estricta, compara agregado a
   agregado con la base local y muestra una vista previa (nuevos / ya existentes / conflictos / inválidos). Nunca sobrescribe: lo idéntico se
   omite, lo distinto es conflicto visible (por defecto no se importa; opcionalmente como copia con ids nuevos y determinista). Atómica,
   idempotente y verificada en la misma transacción.
2. **Sincronización v2** con control de versiones optimista (revisión por fila + `baseRev`), sin depender de la hora del móvil: conflictos
   explícitos a nivel de agregado, tombstones sin resurrección, idempotencia por contenido, grupos atómicos, cola persistente (filas `dirty`),
   WorkManager con reintentos exponenciales.
3. **Incorporación inicial** obligatoria antes de cualquier llamada de red: vista previa de solo lectura, copia recuperable verificada,
   confirmación expresa, aplicación atómica y verificación posterior.
4. Escenario **Samsung ↔ POCO** probado con datos sintéticos equivalentes (9 entrenos/5 rutinas/3 logros vs 2/2/0, mismos UID): ambos acaban
   con los 9 entrenamientos únicos, contenido idéntico fila a fila, sin duplicados ni pérdidas, por servidor y por importación JSON + sincronización.

## 2. Arquitectura anterior (verificada contra el código)
Room (`gymflow.db`, v2) es la fuente local; 1.1 no escribe datos de entrenamiento en Firestore; sincronización opcional con servidor propio
(Ktor + PostgreSQL) desactivada por defecto (URL vacía); protocolo v1 = *last-write-wins* por `updatedAt` del móvil; exportación JSON/CSV sin
importación; `userId` en todas las consultas. Los hallazgos de la auditoría previa se confirmaron.

## 3. Arquitectura nueva
```
UI Compose (ImportScreen, SyncScreen)  →  ImportSyncViewModel  →  SyncService / SyncManager (+ WorkManager: SyncWorker)
                                                                        │
                           RoomImportStore / RoomSyncStore  (Room, transaccional)  ←  RoomRecordAccess (SQL genérico por usuario)
                                                                        │
                    :syncCore (Kotlin/JVM puro, probado en JVM): ExportParser · AggregateView · ImportPlanner/Executor ·
                    Onboarding · SyncRunner · ConflictResolver · BackupFormat · ServerUrl · ConflictCodec
                                                                        │ HTTPS + ID token de Firebase
                                              Servidor Ktor: SyncRepository (rev/baseRev) + PostgreSQL (V4)
```
Room = fuente local; servidor = intercambio; WorkManager = ejecución diferida. Un único mecanismo de sincronización (el anterior
`SyncEngine` se elimina). `SyncLock` (mutex) impide que importación, incorporación y sincronización modifiquen datos a la vez.

## 4. Entidades afectadas
Datos de usuario: **sin cambios de esquema** (`exercises`, `routines`, `routine_*`, `workouts`, `workout_*`, `body_measurements`, `schedules`,
`achievements`). Nuevas (Room v3, `data/SyncEntities.kt`): `sync_revs` (revisión del servidor por fila y servidor), `sync_state` (cursor,
incorporado, último éxito/error por usuario y servidor), `sync_conflicts`, `sync_archive`. La revisión va **fuera** de las tablas de datos a
propósito: el repositorio reconstruye entidades al editar y borraría una columna `baseRev`.

## 5. Importación JSON
`synccore/ExportParser.kt` (formato real de `DataExporter`): tamaño ≤ 20 MB, profundidad ≤ 12, límites por colección, `schemaVersion`==1, tipos,
obligatorios, fechas ISO estrictas (1970–2100), UUID, rangos numéricos, posiciones repetidas, ids duplicados (todos los implicados se
invalidan), `userId` del archivo **solo informativo** (identidad = sesión activa; otra cuenta ⇒ bloqueado salvo confirmación expresa; las filas
siempre se escriben con el uid de la sesión). Errores de archivo ⇒ rechazo total; errores de un registro ⇒ solo ese registro es inválido.
**Campos que el formato no contiene** (no se inventan; se derivan): `createdAt`/`updatedAt` de hijos (del padre), `createdAt` del entreno
(=`startedAt`), id de las series de rutina (derivado de forma estable), `supersetGroup` de rutinas, `deletedAt`; el formato exporta fechas al
segundo (la comparación se hace a esa precisión).

## 6. Deduplicación
`AggregateView` compara solo lo que el formato contiene (sin `createdAt`, grupo muscular derivado ni ids de series de rutina). Raíz nueva ⇒
insertar; mismo id y misma vista ⇒ omitir (idempotente); mismo id y vista distinta ⇒ conflicto con rutas de campos que difieren; borrado local ⇒
`DELETED_LOCALLY` (no se resucita); colisión de ids hijos ⇒ conflicto; **distinto id y mismo nombre ⇒ NO se deduplica**, solo aviso.
Importar como copia usa ids derivados del hash del archivo: repetir la importación no duplica.

## 7. Resolución de conflictos
Importación: por defecto se conserva lo local; el usuario puede importar el conflicto como copia. Sincronización: conflicto **por agregado**
(rutina o entreno completo) para no mezclar versiones; el agregado queda congelado (no se sube ni se aplica remoto) hasta resolverlo. Opciones:
*Conservar este móvil* (rebasa la revisión; las filas solo-servidor se retiran con tombstone) y *Usar el servidor* (lo local se archiva en
`sync_archive` antes de descartarse). La resolución exige haber descargado todas las versiones del servidor (`complete`). Resolución automática
solo si el contenido es idéntico. No hay fusión por campos (requeriría la versión base).

## 8. Protocolo de sincronización (v2)
`PushChange{table,id,baseRev,updatedAt,deletedAt,data,group}` → servidor, por fila: inexistente ⇒ aceptar (rev 1); mismo contenido ⇒ `unchanged`
(idempotente, sin mover el cursor); `baseRev == rev` ⇒ aceptar (rev+1); si no ⇒ `conflict` con la versión del servidor. `group` = todo o nada
(`blocked` para los demás). `PullResponse` incluye `rev`. Cliente: sube agregados completos (padres primero), nunca marca limpia una fila editada
mientras subía, pull incremental con cursor persistido por página, `reset` del servidor ⇒ todo local vuelve a ser subible (sin pisar). Un corte a
mitad de la subida no pierde ni duplica nada (reintento idempotente). Errores: red/servidor ⇒ reintentable; sesión/identidad/rechazo ⇒ no.

## 9. Seguridad del servidor
Sin cambios en la validación del ID token (firma de Google, emisor, audiencia); el UID sale **solo** del token; datos aislados por `user_id`;
nuevas validaciones: `baseRev ≥ 0`, grupo ≤ 100 caracteres, sin cambios repetidos en un lote, límites existentes (≤ 2000 cambios, 64 KB/registro,
10 MB, fecha ≤ +24 h). HTTPS obligatorio en el cliente (`ServerUrl`: solo `https://`; `http://` únicamente a 10.0.2.2/localhost en depuración;
sin usuario/ruta/parámetros), sin redirecciones, sin secretos en la APK ni en logs, mensajes de error genéricos. Pruebas de aislamiento entre usuarios
y de conflictos sin fuga de datos ajenos.

## 10. Migraciones Room
`MIGRATION_2_3` solo crea las 4 tablas nuevas (`Migrations.kt`); no toca filas ni tablas de datos; sin `fallbackToDestructiveMigration`.
Servidor: `V4__sync_revisions.sql` (`rev` con valor 1 en filas existentes; comprobado en el test de migración). **Pendiente:** `app/schemas/.../3.json`
(lo genera Room al compilar) y la ejecución de `MigrationTest.migrate2To3…` en emulador.

## 11. Cambios de interfaz
Perfil → **Importar datos** (selector de documentos del sistema, sin permisos de almacenamiento) y **Sincronización** rehecha: servidor, estado,
última sincronización correcta, pendientes, conflictos, errores, revisar incorporación, sincronizar/reintentar, resolver conflictos, vincular panel.
Todo en español. Sin éxito mostrado antes de una verificación real (resultado de importación/incorporación = verificación transaccional).

## 12. Pruebas ejecutadas
| Suite | Comando | Resultado |
|---|---|---|
| `syncCore` (JVM) | `cd syncCore && gradle test` | **56 / 56 correctas** (ExportParser 12, ImportPlanner 14, SyncScenario 26, Codecs 3, ContentContract 1) |
| Servidor (JVM + PostgreSQL embebido) | `cd server && gradle test` (JDK 21 con `jvmToolchain(21)` en copia temporal; el repo conserva 17) | **49 / 49 correctas** (13 nuevas `SyncRevisionTest`; 36 existentes: 5 adaptadas al protocolo v2 y el antiguo test de *last-write-wins* por fecha sustituido por `SyncRevisionTest`) |
| Comprobación de tipos de la capa Android contra stubs | compilación Kotlin de `RoomRecordAccess`, `RoomStores`, `SyncApi`, `SyncManager`, `SyncService`, `SyncEntities`, `ImportSyncViewModel` con stubs de androidx/Firebase/WorkManager | sin errores (salvo carencias de mi stub de `WorkManager.Result`); **no sustituye a compilar** |
No ejecutadas: ver sección 14.

## 13. Resultados exactos relevantes
Cubiertos por las pruebas: JSON válido/vacío/corrupto/versión/UID/tamaño/profundidad/valores/ids duplicados/referencias; importación repetida;
conflicto de contenido; rutinas con mismo nombre y distinto id; fallo a mitad de transacción (escrituras 1, 5, 12, 20: sin filas parciales);
conservación de datos locales; cancelación; cambio de base entre vista previa y confirmación (`StaleImportPlanException`); Samsung↔POCO por
servidor y por importación; offline concurrente (creación y edición); conflicto con reloj “más reciente”; tombstones y no resurrección; corte de
red parcial en subida y en descarga; token caducado; sin conexión; uid distinto/aislamiento; servidor vacío/antiguo/restaurado; paginación;
sincronización tras importar; operaciones repetidas; carrera de 8 dispositivos (servidor: solo 1 aceptado); importación y sync no concurrentes.

## 14. Limitaciones y pruebas NO ejecutadas
- **Android no compilado**: Room/KSP (incluida la generación de `3.json`), Compose, WorkManager, Firebase, `RoomRecordAccess` (SQL genérico y
  mapeo de tipos) y las 3 pruebas instrumentadas nuevas (`RoomStoresTest`, `MigrationTest.migrate2To3…`) están sin ejecutar. Tampoco
  `gradle :app:testDebugUnitTest` (incluye las pruebas de Google Sign-In v3 y `SyncContractTest` adaptada) ni `assembleDebug`. La resolución de plugins del
  build multi-proyecto (`:syncCore` con Kotlin JVM junto a AGP) no se pudo probar.
- Regresiones (Google Sign-In v3, correo, Room, estadísticas, exportación, offline) **no verificadas por ejecución**: los archivos de login/auth
  no se han tocado (diff vacío) y `applicationId`/firma no cambian, pero eso no sustituye a las pruebas.
- Compatibilidad de firma para actualizar sobre una instalación existente: **sin evidencia** (no se inspeccionó ninguna APK instalada).
- Fusión por campos no existe; conflictos que cruzan el límite de página de descarga pueden dividir un agregado (se explica en `SyncRunner`).
- Rendimiento: el motor lee todas las filas del usuario en cada ronda del lote (`O(n·lotes)`); aceptable para miles de filas, mejorable.
- Formato de copia: ids de series de rutina y `supersetGroup` de rutinas no se pueden recuperar de la copia actual (se armonizan al incorporar).

## 15. Riesgos residuales
1. Errores de compilación/ejecución en la capa Android (la más probable fuente de fallos en la primera compilación).
2. `RoomRecordAccess` usa SQL genérico: un tipo de columna inesperado o una restricción NOT NULL con datos remotos incompletos fallaría (la transacción
   se revierte, sin pérdida).
3. Servidor desplegado con la migración V4 y apps antiguas: sus ediciones de filas existentes pasan a conflicto (diseño seguro, pero requiere actualizar las apps).
4. Fiabilidad de relojes: ya no decide conflictos, pero `updatedAt` se sigue validando (+24 h) en el servidor.
5. Pérdida por desinstalar la app antes de exportar/incorporar: la copia privada se pierde con la app (por eso se exige copia externa).

## 16. Configuración pendiente
Servidor propio desplegado (no hecho) y su URL; aplicar V4; ejecutar `gradle :app:assembleDebug`/pruebas instrumentadas en emulador desechable;
generar y revisar `app/schemas/com.gymflow.data.GymDatabase/3.json`; revisar firmas de las APK de ambos móviles.

## 17. Plan de despliegue futuro (NO ejecutado)
1) Auditoría Codex y corrección. 2) Backup del servidor y de cada móvil (JSON). 3) Desplegar servidor con V4 (`deploy/README.md`). 4) Compilar APK
(misma `applicationId` y firma que las instaladas; comprobar `apksigner`). 5) Instalar sobre la existente (sin desinstalar). 6) Incorporar un móvil,
comprobar, luego el otro. 7) Observar conflictos.

## 18. Plan de recuperación
Copia JSON de cada móvil (existente) + copia cruda verificada antes de incorporar (`files/backups/pre-sync-*.json`, formato `BackupFormat`, relegible por
`BackupFormat.parse`) + copia externa elegida por el usuario + `sync_archive` (versiones descartadas) + tombstones (nada se borra físicamente salvo
duplicados verificados sin subir) + backup del servidor (`deploy/backup.sh`). La importación nunca sobrescribe.

## 19. Procedimiento para incorporar Samsung y POCO
*Camino A (sin servidor, usable en cuanto compile):* en el POCO, Perfil → Importar datos → JSON del Samsung (previsto: 7 entrenamientos nuevos, 2 idénticos,
5 rutinas y 3 logros nuevos; las 2 rutinas del POCO se conservan; rutinas con igual nombre se avisan sin fusionar); después exportar del POCO e importar
en el Samsung para recibir las 2 rutinas del POCO. *Camino B (servidor):* Samsung primero (servidor vacío ⇒ todo se sube), después POCO (descarga los 7
entrenamientos, adopta los 2 idénticos, sube sus 2 rutinas). En ambos: no desinstalar; conservar los JSON originales fuera de los móviles.

## 20. Evidencia para la auditoría de Codex
- Commits (rama `feature/import-sync-multidevice`, base `895f1fb`): ver `git log --oneline 895f1fb..HEAD`.
- Núcleo: `syncCore/src/main/kotlin/com/gymflow/synccore/{Records,ExportParser,AggregateView,ImportPlanner,Onboarding,SyncRunner,Protocol,Codecs}.kt`; pruebas en
  `syncCore/src/test/…` (`SyncScenarioTest`, `ImportPlannerTest`, `ExportParserTest`, `Fixtures`, `FakeServer`, `Devices`).
- Servidor: `server/src/main/kotlin/com/gymflow/server/{SyncRepository,Models,Module,ContentEquality}.kt`, `db/migration/V4__sync_revisions.sql`,
  `SyncRevisionTest.kt`, `contract/content-equality-cases.json` (cliente y servidor comparten los casos).
- Android: `app/src/main/java/com/gymflow/{data/SyncEntities,data/Migrations,data/GymDatabase,sync/*,ImportSyncViewModel,ImportScreen,SyncScreen}.kt`.
- Preguntas de auditoría prioritarias: (1) ¿puede alguna ruta de código subir/bajar datos antes de la incorporación? (2) ¿puede una edición vía
  `GymRepository` perder la revisión base? (3) ¿hay una secuencia donde `ImportExecutor`/`Onboarding.apply` deje estado parcial? (4) corrección del SQL
  genérico y de las clases de Room; (5) equivalencia de `Content.same` y `ContentEquality.same`; (6) ámbitos por usuario en `scopeSql`.
- Reproducir: `cd syncCore && gradle test` · `cd server && gradle test` (JDK ≥ 17; con JDK 21 ajustar `jvmToolchain` solo localmente).
