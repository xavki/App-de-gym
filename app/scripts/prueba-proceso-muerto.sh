#!/usr/bin/env bash
# Recuperación del entreno en curso tras matar el proceso de verdad (WorkoutDraftTest, @ProcesoReal).
# Requiere el APK de la app y el de tests ya instalados en el EMULADOR:
#   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
#   adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
#   adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Uso: app/scripts/prueba-proceso-muerto.sh emulator-5554
# Solo funciona en emuladores: nunca toca un móvil real.
set -euo pipefail

SERIAL="${1:?Indica el serial del emulador (adb devices), p. ej. emulator-5554}"
ADB="${ADB:-adb}"
RUNNER="com.gymflow.test/androidx.test.runner.AndroidJUnitRunner"
CLASS="com.gymflow.WorkoutDraftTest"
export MSYS_NO_PATHCONV=1   # Git Bash en Windows: no convertir rutas del dispositivo

if [ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]; then
    echo "ERROR: $SERIAL no es un emulador; esta prueba no se ejecuta en dispositivos reales." >&2
    exit 2
fi

fase() {
    local out
    out="$("$ADB" -s "$SERIAL" shell am instrument -w -e class "$CLASS#$1" "$RUNNER" | tr -d '\r')"
    echo "$out" | tail -n 3
    echo "$out" | grep -q "^OK (1 test)" || { echo "ERROR: falló $1" >&2; exit 1; }
}

fase fase1GuardarEntrenoEnCurso
"$ADB" -s "$SERIAL" shell am force-stop com.gymflow
if [ -n "$("$ADB" -s "$SERIAL" shell pidof com.gymflow | tr -d '\r')" ]; then
    echo "ERROR: el proceso com.gymflow sigue vivo tras force-stop" >&2
    exit 1
fi
echo "Proceso com.gymflow terminado."
fase fase2RecuperarTrasMatarElProceso
echo "OK: el entreno en curso se recupera tras matar el proceso."
