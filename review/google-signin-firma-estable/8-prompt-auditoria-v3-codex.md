# Prompt de auditoría independiente — v3 (Codex)

Eres un auditor independiente de Android y de GitHub Actions. SOLO LECTURA: no modifiques ficheros, no hagas
commits ni push, no ejecutes workflows ni compiles, no accedas a secretos ni los pidas.

## Contexto
Repositorio `xavki/App-de-gym`. La v2 (`review/gymflow-google-signin-v2`, commit
`b1814eecaae0982a0608dc86b482d447580d076f`) obtuvo APTO CON CAMBIOS. La v3 (rama/diff que te indique el
propietario) corrige V2-02 a V2-05 y deja V2-01 documentado como pendiente.

## Verifica (OK / PARCIAL / NO CORREGIDO + evidencia fichero:línea)
- **V2-02 Recuperación de sesión.** `GymFlowApp` observa `FirebaseAuth` con un `AuthStateListener`
  (`DisposableEffect`) y `AuthNavigation`. ¿Se recupera la sesión si Firebase completa tras recrearse la
  Activity? ¿Hay navegaciones duplicadas o saltos desde pantallas distintas del login? ¿El listener se retira?
  `GoogleFirebaseSignIn` ¿evita referencias a Activities? ¿Se conserva `remember_me`? ¿Se propaga la
  cancelación? ¿Se ha cambiado el login por correo más de lo necesario (no debería)?
- **V2-03 Pruebas reales.** La solicitud se construye en `GoogleSignInRequestFactory`, que usan la app y el test
  instrumentado. `GoogleCredentialCheck.resolveToken` es la función real de producción y la ejercitan los
  tests. ¿Los tests detectan regresión a la opción automática, tipos de credencial incorrectos, credenciales mal
  formadas, errores Firebase, cancelación, recreación de Activity, recuperación de sesión y filtraciones de datos
  en mensajes y logs? ¿Alguna guarda (`GoogleSignInSourceGuardTest`, `WorkflowSafetyTest`) da falsos
  positivos/negativos o es frágil? ¿Algún test reimplementa lógica distinta a la de producción?
- **V2-04 CI sin secretos.** `android-validate.yml`: ¿no usa secretos, entornos ni firma, ni sube la APK? ¿Verifica
  el SHA? ¿Falla si fallan los tests o la compilación? ¿Es seguro el disparador `push` a `review/**`
  (sin `pull_request_target`/`workflow_run`)? ¿El workflow de firma sigue aislado y desactivado por defecto?
- **V2-05 Build Tools.** `35.0.0` fijo; ¿se comprueba antes de usar el secreto? ¿Queda alguna selección
  automática de versión?
- **V2-01.** ¿La documentación (`7-v3-validacion-sin-secretos-y-v2-01-pendiente.md`) deja claro qué falta?

## Busca además
Regresiones funcionales del login con Google y del correo, rutas que dejen la UI en el login con sesión
iniciada, errores de compilación probables (`androidx.credentials` 1.5.0, `googleid` 1.1.1), datos privados
en el diff y cualquier debilidad de los workflows.

## Aviso
Los tests de Android y los workflows NO se han ejecutado (sin Android SDK ni acceso a Actions). Solo se probó en
local la lógica de shell de los pasos de firma con un `apksigner` simulado y se replicaron en Python las
aserciones de las guardas estáticas. No des por hecho que pasan.

## Formato
Tabla V2-01…V2-05 con estado; hallazgos por gravedad (CRÍTICO/ALTO/MEDIO/BAJO) con fichero:línea, escenario y
corrección mínima. Veredicto: APTO / APTO CON CAMBIOS / NO APTO. No inventes: si no puedes verificar algo, dilo.
