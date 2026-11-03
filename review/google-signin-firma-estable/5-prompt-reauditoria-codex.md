# Prompt de REAUDITORÍA independiente (Codex)

Eres un auditor independiente de seguridad de GitHub Actions y de Android. Modo SOLO LECTURA: no modifiques
ficheros, no hagas commits ni push, no ejecutes workflows ni compiles, no accedas a secretos.

## Qué se revisa
Repositorio `xavki/App-de-gym`. Ya auditaste la rama `backup/gymflow-google-signin-review` (commit
`15ae83981c1b4cf423e497992519574035e48fd1`) con veredicto APTO CON CAMBIOS (hallazgos H-01 a H-08).
Ahora se revisan las correcciones, entregadas como diff sobre ese commit (fichero `correcciones-h01-h08.patch`,
o la rama/commit que te indique el propietario). Verifica cada hallazgo y busca regresiones y hallazgos nuevos.

## Contexto
App Android GymFlow 1.1 (`com.gymflow`, Kotlin, Compose, Room, Firebase Auth). El cambio sustituye
`GetGoogleIdOption` por `GetSignInWithGoogleOption` en el botón de Google y añade un workflow que firma una APK
debug con una clave estable guardada como secreto de GitHub.

## Qué NO debe haber cambiado
Room y sus datos, nutrición, entrenamientos, login por correo y contraseña (`LoginScreen.kt`), Firebase Auth
(salvo el tratamiento del resultado de `signInWithCredential` en `MainActivity.kt`), `google-services.json`,
`applicationId`, `versionCode/versionName`.

## Verifica cada hallazgo (OK / PARCIAL / NO CORREGIDO + evidencia fichero:línea)
- **H-01 Exposición del keystore.** Workflow en tres trabajos. ¿El trabajo `sign` ejecuta algún código del
  repositorio, Gradle o tests? ¿Tiene checkout? ¿Puede otra rama o un workflow modificado leer el secreto
  (entorno `apk-signing`, `permissions`, `if: github.ref`)? ¿Acciones fijadas a SHA completo? (comprueba que cada
  SHA corresponde a la etiqueta indicada en el repositorio oficial). ¿Se documentan las limitaciones
  (`6-limitaciones-seguridad.md`)?
- **H-02 Verificación de firma.** ¿Se comprueba el código de salida de `apksigner verify`? ¿`set -euo pipefail`?
  ¿Un único firmante y un único certificado, SHA-1 y SHA-256? ¿Falla si la salida es ambigua o incompleta?
  ¿No se publica nada si falla?
- **H-03 Selección del código.** ¿SHA completo, aprobado en `.github/approved-commits.txt`, `git rev-parse HEAD`
  idéntico, sin predeterminado móvil, fallo antes de llegar al secreto? ¿Inyección en `inputs.commit_sha`?
- **H-04 Privacidad.** ¿Ya no hay `exception.message` de Firebase ni de Credential Manager en Toast o logs del
  flujo de Google? ¿Los códigos de diagnóstico son valores fijos? Revisa también `FirebaseService.saveUser`.
- **H-05 Ciclo de vida.** `addOnCompleteListener` sin Activity en `MainActivity.signInWithGoogle`: ¿se pierde el
  resultado si la Activity se para o se recrea? ¿Se conserva la cancelación (`CancellationException` relanzada)?
  ¿Fugas de Activity? ¿Recuperación de sesión (`SessionPolicy`, `remember_me`, `currentUser`)?
- **H-06 Pruebas.** Revisa `GoogleSignInErrorsTest.kt`, `GoogleSignInSupportTest.kt` (JVM) y
  `GoogleSignInRequestTest.kt` (instrumentado). ¿Cubren solicitud, tipo de credencial, credenciales malformadas,
  cancelaciones, errores Firebase, sesión y ausencia de filtraciones? ¿Alguna aserción es vacía o frágil?
  ¿Compilarían con `androidx.credentials:1.5.0` y `googleid:1.1.1`?
- **H-07 Diagnóstico.** ¿`NoCredentialException` ya no afirma que falte una cuenta?
- **H-08 Documentación.** SHA-1 completa correcta: `8F:22:5A:7F:7B:38:D5:E3:04:61:FD:34:5E:6E:63:0F:39:0B:56:88`
  (= `8f225a7f7b38d5e30461fd345e6e630f390b5688` en `app/google-services.json`).

## Busca además
Regresiones funcionales en el login con Google, cualquier ruta que deje `onResult` sin llamar, errores de
compilación probables, secretos o datos personales en el diff, y riesgos de despliegue (instalar la APK sobre
otras con firma distinta).

## Aviso sobre ejecución
Los tests de Android NO se han ejecutado en CI ni en el entorno del autor (sin acceso al SDK de Android). Solo se
probó en local la lógica de shell del workflow con un `apksigner` simulado. No des por hecho que pasan.

## Formato
Tabla de hallazgos por gravedad (CRÍTICO/ALTO/MEDIO/BAJO) con fichero:línea, escenario de fallo y corrección
mínima. Una tabla H-01…H-08 con estado. Veredicto final: APTO / APTO CON CAMBIOS / NO APTO.
No inventes: si no puedes verificar algo, dilo.
