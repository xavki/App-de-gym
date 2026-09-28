#!/usr/bin/env sh
# Copia de seguridad diaria de PostgreSQL (guarda las últimas 14).
# Programarla en la VM:  crontab -e  →  0 4 * * * /ruta/a/App-de-gym/deploy/backup.sh
set -eu
cd "$(dirname "$0")"
mkdir -p backups
docker compose exec -T db pg_dump -U gymflow gymflow | gzip > "backups/gymflow-$(date +%F).sql.gz"
find backups -name 'gymflow-*.sql.gz' -mtime +14 -delete
