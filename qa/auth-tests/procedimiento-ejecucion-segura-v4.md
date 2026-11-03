# Procedimiento de ejecución segura — AuthLifecycleTest (v4)

NO ejecutar nada de esto hasta recibir autorización. Se hace en el PC del propietario. SERIAL = el que aparezca
para GymFlow_Test_API35 (p. ej. emulator-5554). Todos los comandos ADB llevan `-s SERIAL`.

## A. Preparación (con Internet)
1. Copia temporal del repositorio en el commit aprobado + parche v4. Sin `gymflow.syncUrl` en local.properties
   (si existe, la guarda de sincronización bloquea la ejecución).
2. Compilar con Android Studio o Gradle 8.11.1 (no instala nada en dispositivos):
   `gradle :app:assembleDebug :app:assembleDebugAndroidTest`
   APK: app/build/outputs/apk/debug/app-debug.apk y app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
3. Firebase CLI (ya instalada por el propietario): preparar `firebase.json` local con auth 9099 y firestore 8080.

## B. Dispositivos (obligatorio ANTES de instalar nada)
4. Desconectar FÍSICAMENTE el Samsung y el POCO (cable USB) y desactivar la depuración inalámbrica en ambos.
5. `adb kill-server` y `adb start-server`; después `adb devices -l`.
   Debe aparecer UNA sola línea, `emulator-XXXX`. Cualquier otra línea → PARAR.
6. Comprobar el AVD: `adb -s SERIAL emu avd name` → debe devolver exactamente `GymFlow_Test_API35`. Si no → PARAR.
7. Anotar el SERIAL y usar `adb -s SERIAL` en TODOS los comandos. No usar `adb install` sin `-s`.

## C. Red y emuladores
8. Arrancar los emuladores locales: `firebase emulators:start --only auth,firestore --project demo-gymflow`.
9. Arrancar el AVD con DNS inservible (capa adicional, NO la garantía): `emulator -avd GymFlow_Test_API35 -dns-server 192.0.2.1`.
10. DESCONECTAR el PC de Internet (Wi-Fi apagado o cable fuera) cuando las APK ya están compiladas y los emuladores
    arrancados. Los emuladores y 10.0.2.2 siguen funcionando sin Internet.
11. Verificar el token falso (en el PC, contra el emulador local); debe devolver `idToken` y `localId`; si no → PARAR:
    `curl -s -X POST "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=fake-api-key" -H "Content-Type: application/json" -d "{\"postBody\":\"id_token=%7B%22sub%22%3A%22s1%22%2C%22email%22%3A%22a%40example.invalid%22%2C%22email_verified%22%3Atrue%7D&providerId=google.com\",\"requestUri\":\"http://localhost\",\"returnIdpCredential\":true,\"returnSecureToken\":true}"`

## D. Instalación y limpieza (solo en el AVD)
12. Repetir el paso 5 (`adb devices -l`: solo `emulator-XXXX`).
13. `adb -s SERIAL install -r app\build\outputs\apk\debug\app-debug.apk`
14. `adb -s SERIAL install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`
15. Limpiar SOLO los datos de la app en el AVD: `adb -s SERIAL shell pm clear com.gymflow`

## E. Ejecución (solo esta clase)
16. `adb -s SERIAL shell am instrument -w -e class com.gymflow.auth.AuthLifecycleTest com.gymflow.test/androidx.test.runner.AndroidJUnitRunner`

## F. Criterio de aceptación (cualquier otra cosa NO es un éxito)
- `OK (13 tests)` y ningún `FAILURES!!!` ni `Process crashed`.
- Exactamente UNA prueba ignorada (`INSTRUMENTATION_STATUS_CODE: -3`, la marcada con @Ignore); ninguna con código `-4`
  (suposición no cumplida) ni omitida de otro modo.
- Ningún mensaje «EJECUCIÓN BLOQUEADA» ni «ERROR REST DEL EMULADOR». Un bloqueo = ejecución NO válida.

## G. Cierre
17. Parar emuladores y AVD. Reconectar Internet. No reconectar teléfonos hasta terminar.
