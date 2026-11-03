# Prompt de auditoría independiente (Codex)

Eres un auditor independiente de seguridad y de Android. Revisa SOLO LECTURA el cambio descrito;
no modifiques ficheros, no hagas commits ni push, no ejecutes workflows.

## Contexto
Repositorio `xavki/App-de-gym`. App Android GymFlow 1.1 (`applicationId com.gymflow`), Kotlin, Compose,
Room, Firebase Auth. Se prepara un cambio en dos partes (en los parches `1-codigo-android.patch` y
`2-workflow.patch`; la base es el commit `9e9a5bf` de `feature/room-offline-first` para el código, y `master`
para el workflow):

1. **Google Sign-In**: el botón «Entrar con Google» pasa de `GetGoogleIdOption(filterByAuthorizedAccounts=false)`
   a `GetSignInWithGoogleOption`, con gestión de errores por tipo y mensajes en español
   (`GoogleSignInErrors.kt`, `MainActivity.kt`, test `GoogleSignInErrorsTest.kt`).
2. **Firma debug estable** en GitHub Actions: el workflow restaura `~/.android/debug.keystore` desde el secreto
   `DEBUG_KEYSTORE_BASE64`, compila `assembleDebug`, verifica que el SHA-1 del certificado es
   `8f225a7f7b38d5e30461fd345e6e630f390b5688` y falla si no coincide o falta el secreto.

## Qué NO debe cambiar
Firebase Authentication (login por correo y contraseña incluido), Room y sus datos, `google-services.json`,
nutrición, entrenamiento, `applicationId`, `versionCode/versionName`.

## Qué auditar (responde punto por punto: OK / PROBLEMA + evidencia + corrección mínima)
### A. Código
1. ¿`GetSignInWithGoogleOption.Builder(serverClientId).build()` es la API correcta en `googleid:1.1.1` y
   `credentials:1.5.0`? ¿Compila con esas versiones (comprueba firmas reales de las librerías si puedes)?
2. ¿Se comprueba bien `CustomCredential` + `TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` antes de `createFrom`?
3. Gestión de excepciones: orden de los `catch`, `CancellationException` relanzada, `GetCredentialException`
   vs. `GoogleIdTokenParsingException`. ¿Hay algún camino que deje la UI colgada (`onResult` sin llamar)?
4. ¿Cubre `GoogleSignInErrors.classify` los tipos relevantes? ¿Hay clases de `androidx.credentials.exceptions`
   que merezca la pena distinguir y no se distinguen?
5. Privacidad: confirma que ni el log ni el Toast incluyen token, correo, UID ni el mensaje de la excepción
   (solo clase y `type`). Revisa `diagnosticCode` y `userText`.
6. ¿El test unitario puede ejecutarse en JVM puro con las clases reales de `androidx.credentials`
   (sin Robolectric)? ¿Cubre cancelación, sin cuenta, proveedor ausente, no soportado, interrumpido, desconocido
   y la no filtración de datos?
7. Confirma que no hay cambios en Room, Firebase Auth, correo/contraseña ni nutrición.

### B. Workflow y firma
1. ¿El secreto puede filtrarse a los logs? Revisa `env`, `set +x`, expansión de variables, mensajes de error,
   resumen del job y artefactos.
2. ¿El keystore queda en algún sitio persistente (caché de Gradle, artefactos, `upload-artifact`)?
   ¿Se borra siempre (`if: always()`)? ¿Permisos del fichero?
3. ¿Falla de forma segura si el secreto falta, no es Base64, no abre con `android` o no tiene el alias
   `androiddebugkey`? ¿Y si la huella no coincide? ¿Se evita publicar la APK en esos casos?
4. ¿Android Gradle Plugin usará de verdad `$HOME/.android/debug.keystore` en `ubuntu-latest`
   (variables `ANDROID_USER_HOME` / `ANDROID_SDK_HOME`)? ¿La verificación posterior lo detecta si no?
5. Parseo de `apksigner verify --print-certs`: ¿es robusto frente a distintas versiones de build-tools y varios
   firmantes (v1/v2/v3)?
6. Permisos mínimos (`contents: read`), disparador solo `workflow_dispatch`, entradas del usuario
   (`inputs.ref`) sin inyección de comandos en `run:`, acciones fijadas (`@v4`).
7. Riesgos de exfiltración del secreto por quien tenga permiso de escritura en el repo.

### C. Riesgos de despliegue
- La APK con firma `8f225a7f…` ¿podría actualizar una instalación existente de GymFlow firmada con la misma
  clave? ¿Y fallar con `INSTALL_FAILED_UPDATE_INCOMPATIBLE` en el POCO (firmada hoy con otra clave debug)?
- Impacto sobre Google Sign-In con la huella ya registrada en Firebase.
- Cualquier otro riesgo que veas.

## Formato de respuesta
Tabla de hallazgos ordenada por gravedad (CRÍTICO/ALTO/MEDIO/BAJO), cada uno con fichero:línea, explicación,
escenario de fallo y corrección mínima propuesta. Termina con un veredicto: APTO / APTO CON CAMBIOS / NO APTO.
No inventes: si no puedes verificar algo, dilo.
