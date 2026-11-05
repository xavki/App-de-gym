# INFORME — Corrección puntual R7-01 (carrera del fixture con el callback inicial de FirebaseAuth)

**Rama local:** `feature/import-sync-multidevice` · **HEAD de partida:** `c2c105d8fe9ca7764fc575c83d382d30fac6e8c9`
**Estado:** **CORRECCIÓN R7-01 IMPLEMENTADA — PENDIENTE DE CODEX.** No compilado ni ejecutado en Android (sin SDK/Compose en Linux): ningún test se declara aprobado.

Alcance: solo `app/src/androidTest/java/com/gymflow/HomeProgressReloadTest.kt` (+84/−9). Sin cambios de producción, Gradle, dependencias, Room, Firebase ni SessionGuard; `clearData()` y el listener productivo intactos. Sin push, merge, PR, release, instalación ni producción.

## 1. Causa (confirmada en el informe de Codex)
`setUp` creaba `GymFlowViewModel` sin esperar su callback inicial: el `AuthStateListener` del ViewModel recibe una notificación inicial sin usuario y llama a `clearData()`. Si el test sembraba `AUDIT OLD WORKOUT` antes de que corriera, el historial quedaba vacío y `shown("AUDIT OLD WORKOUT")` fallaba. Defecto del fixture, no regresión productiva.

## 2. Corrección (determinista, sin esperas fijas)
Preparación común a los **cinco tests** (`@Before setUp`):
1. El ViewModel se crea **en el hilo UI** (`runOnMainSync`) mediante `ViewModelProvider(store, factory)`, con un **`ViewModelStore` de prueba**.
2. En ese mismo bloque se registra un listener «sonda» **después** del del ViewModel. Los callbacks iniciales de ambos se encolan en el hilo principal; la sonda cuenta atrás un `CountDownLatch` (sincronización observable; el `await` es solo un tope de seguridad, no una espera para «dar tiempo»).
3. `Instrumentation.waitForIdleSync()` **drena la cola del hilo principal**: al volver, el callback inicial del ViewModel (y su `clearData()`) ya se ejecutó, con independencia del orden relativo de los dos callbacks.
4. Solo entonces se **siembra** el historial/catálogo sintético **en el hilo UI** (`seed { … }`) y se espera reposo con `compose.waitForIdle()`; después se monta la pantalla.
5. `@After`: `store.clear()` en el hilo UI → `onCleared()` → `auth.removeAuthStateListener(authListener)`; después se cierra la base. La sonda se retira en `setUp`.

Sin `Thread.sleep`, sin `delay`, sin reintentos. Las aserciones de los cinco tests no cambian (visibilidad, orden, cardinalidad, volumen, récords, mapa muscular).

## 3. Verificación de que se retira el listener
Test nuevo `viewModelListenerIsRemovedWhenTheViewModelIsCleared`: localiza por **reflexión sobre el contenido** (colecciones de `FirebaseAuth` que contienen `AuthStateListener`, no por nombres ofuscados), exige exactamente **1** listener de `GymFlowViewModel` antes (también detecta fugas de tests anteriores), hace `store.clear()` y exige **0** después. Si la colección no se pudiera localizar, falla con un mensaje explícito (verificación no fiable) en lugar de pasar en silencio; está aislado en un test propio para no afectar a los cinco originales.

## 4. Limitaciones / hipótesis a confirmar por Codex
- **Hipótesis sobre FirebaseAuth:** el callback inicial se entrega por la cola del hilo principal. `waitForIdleSync()` solo garantiza haberlo drenado si ya estaba encolado al registrar el listener (cierto en ese caso); si el SDK lo entregara en otro hilo, esta sincronización no sería suficiente. La sonda atestigua que al menos una notificación inicial post-registro llegó, pero no hay una señal pública del *propio* callback del ViewModel; por eso se usa el drenado de la cola.
- La reflexión depende de que `FirebaseAuth` guarde sus listeners en una `Collection`; si cambiara, solo falla el test dedicado (6.º).
- No compilado: sintaxis comprobada con el compilador de Kotlin (**0 errores de sintaxis**; los restantes son referencias sin resolver por la ausencia de Android/Compose/Firebase en Linux, p. ej. `'create' overrides nothing`, que deriva de `ViewModelProvider.Factory` sin resolver). APIs usadas (`ViewModelStore`, `ViewModelProvider(ViewModelStore, Factory)`, `ViewModel.onCleared`, `InstrumentationRegistry.getInstrumentation().runOnMainSync/waitForIdleSync`, `FirebaseAuth.addAuthStateListener/removeAuthStateListener`) son estándar y ya están en las dependencias del proyecto; verificadas por conocimiento, no con los artefactos.

## 5. Resultados reales (Linux)
Guardas JVM de `app` (Round3GuardTest 14, LegacyImportGuardTest 5, SchemaFilesGuardTest 3): **22/22, 0 omitidos** (incluyen que el test conserva sus tests originales). No se ejecutó nada instrumentado ni `syncCore`/servidor (sin cambios en ellos; no se trasladan resultados anteriores como nuevos).

## 6. Pendiente (Codex Windows)
`assembleDebugAndroidTest` y `HomeProgressReloadTest` (ahora 6 tests) en AVD desechable; repetir los cinco originales completos y, solo si pasan, retomar la etapa 2.
