#!/usr/bin/env bash
# Pruebas de deploy/backup.sh sin Docker ni base de datos: docker, gzip y date se sustituyen
# por versiones falsas en el PATH. Ejecutar:  bash deploy/test/backup_test.sh
set -uo pipefail
SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/backup.sh"
REAL_GZIP=$(command -v gzip)
passed=0 failed=0

# Cada caso trabaja en una carpeta nueva con una copia del script y sus falsos docker/gzip/date
setup() {
  work=$(mktemp -d)
  mkdir -p "$work/bin" "$work/deploy/backups"
  cp "$SCRIPT" "$work/deploy/backup.sh"
  cat > "$work/bin/docker" <<'EOF'
#!/usr/bin/env bash
# Simula "docker compose exec -T db pg_dump ..." según DUMP_MODE
[ "$*" = "compose exec -T db pg_dump -U gymflow gymflow" ] || { echo "llamada inesperada: $*" >&2; exit 99; }
printf -- '--\n-- PostgreSQL database dump\n--\nCREATE TABLE records (id text);\n'
case "${DUMP_MODE:-ok}" in
  ok) printf -- '--\n-- PostgreSQL database dump complete\n--\n\n\\unrestrict abc\n\n' ;;
  fail) echo "pg_dump: error: connection to server failed" >&2; exit 1 ;;
  truncated) ;; # termina "bien" pero sin la línea final
esac
EOF
  cat > "$work/bin/gzip" <<EOF
#!/usr/bin/env bash
# Con GZIP_FAIL=1 la compresión falla a mitad
if [ "\${GZIP_FAIL:-0}" = 1 ] && [ \$# -eq 0 ]; then head -c 10; echo "gzip: No space left on device" >&2; exit 1; fi
exec "$REAL_GZIP" "\$@"
EOF
  printf '#!/usr/bin/env bash\necho "${FAKE_DATE:-2026-10-27-040000}"\n' > "$work/bin/date"
  chmod +x "$work/bin/"*
}

run() { (cd "$work" && PATH="$work/bin:$PATH" bash deploy/backup.sh) > "$work/out" 2>&1; status=$?; }
backups() { (cd "$work/deploy/backups" && ls -A | sort | tr '\n' ' '); }

check() { # check "descripción" condición...
  local name=$1; shift
  if "$@"; then passed=$((passed + 1)); echo "ok   $name"
  else failed=$((failed + 1)); echo "FAIL $name"; sed 's/^/     /' "$work/out"; fi
}

old_backup() { # copia antigua de hace N días
  echo viejo | "$REAL_GZIP" > "$work/deploy/backups/$1"
  touch -d "$2 days ago" "$work/deploy/backups/$1"
}

# 1. Copia correcta: archivo válido, sin temporales, y borra solo las de más de 14 días
setup
old_backup gymflow-2026-09-01.sql.gz 30
old_backup gymflow-2026-10-20.sql.gz 7
old_backup pre-nutricion-2026-09-01-1200.sql.gz 30
run
check "éxito: sale con 0" test "$status" -eq 0
check "éxito: crea la copia y no deja temporales" test "$(backups)" = "gymflow-2026-10-20.sql.gz gymflow-2026-10-27-040000.sql.gz pre-nutricion-2026-09-01-1200.sql.gz "
check "éxito: la copia es gzip válido y completo" \
  bash -c "\"$REAL_GZIP\" -dc '$work/deploy/backups/gymflow-2026-10-27-040000.sql.gz' | grep -q '^-- PostgreSQL database dump complete'"
rm -rf "$work"

# 2. pg_dump falla: error, sin archivo nuevo, las copias antiguas intactas
setup
old_backup gymflow-2026-09-01.sql.gz 30
DUMP_MODE=fail run
check "pg_dump falla: sale con error" test "$status" -ne 0
check "pg_dump falla: avisa" grep -q "pg_dump o gzip han fallado" "$work/out"
check "pg_dump falla: no deja archivos ni borra los antiguos" test "$(backups)" = "gymflow-2026-09-01.sql.gz "
rm -rf "$work"

# 3. Volcado cortado (sin "dump complete"): no se da por bueno
setup
DUMP_MODE=truncated run
check "volcado incompleto: sale con error" test "$status" -ne 0
check "volcado incompleto: avisa" grep -q "la copia está incompleta" "$work/out"
check "volcado incompleto: no deja archivos" test -z "$(backups)"
rm -rf "$work"

# 4. Falla la compresión
setup
GZIP_FAIL=1 run
check "gzip falla: sale con error" test "$status" -ne 0
check "gzip falla: no deja archivos" test -z "$(backups)"
rm -rf "$work"

# 5. Ya existe una copia con el mismo nombre: no se sobrescribe
setup
echo "copia anterior" > "$work/deploy/backups/gymflow-2026-10-27-040000.sql.gz"
run
check "nombre repetido: sale con error" test "$status" -ne 0
check "nombre repetido: la copia existente sigue igual" test "$(cat "$work/deploy/backups/gymflow-2026-10-27-040000.sql.gz")" = "copia anterior"
check "nombre repetido: no deja temporales" test "$(backups)" = "gymflow-2026-10-27-040000.sql.gz "
rm -rf "$work"

# 6. Lanzado con "sh backup.sh" sigue usando bash (y pipefail)
setup
DUMP_MODE=fail
(cd "$work" && PATH="$work/bin:$PATH" DUMP_MODE=fail sh deploy/backup.sh) > "$work/out" 2>&1; status=$?
check "con sh: un fallo de pg_dump también da error" test "$status" -ne 0
check "con sh: no deja archivos" test -z "$(backups)"
unset DUMP_MODE
rm -rf "$work"

echo "$passed correctas, $failed fallidas"
[ "$failed" -eq 0 ]
