#!/usr/bin/env bash
# Copia de seguridad diaria de PostgreSQL (borra las de más de 14 días).
# Programarla en la VM:  crontab -e  →  0 4 * * * /ruta/a/App-de-gym/deploy/backup.sh
# Si pg_dump o gzip fallan o la copia queda incompleta, sale con error (cron lo avisa),
# no deja ningún archivo y no borra las copias anteriores. Pruebas: deploy/test/backup_test.sh
[ -n "${BASH_VERSION:-}" ] || exec bash "$0" "$@" # p. ej. si se lanza con "sh backup.sh"
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p backups

fail() { echo "backup.sh: $*" >&2; exit 1; }

final="backups/gymflow-$(date +%F-%H%M%S).sql.gz"
tmp=$(mktemp backups/.gymflow-XXXXXX)
trap 'rm -f "$tmp"' EXIT

docker compose exec -T db pg_dump -U gymflow gymflow | gzip > "$tmp" || fail "pg_dump o gzip han fallado"
gzip -t "$tmp" || fail "el archivo comprimido está dañado"
# pg_dump escribe esta línea solo al terminar bien: sin ella la copia está cortada
gzip -dc "$tmp" | tail -n 20 | grep '^-- PostgreSQL database dump complete' > /dev/null || fail "la copia está incompleta"

# ln no sobrescribe: si ya hay una copia con ese nombre, falla y la deja intacta
ln "$tmp" "$final" 2>/dev/null || fail "ya existe $final"
echo "Copia guardada: $final ($(wc -c < "$final") bytes)"

find backups -name 'gymflow-*.sql.gz' -mtime +14 -delete
