# Desplegar GymFlow en tu VM

Arquitectura: la app guarda todo en el móvil (Room) y sincroniza con **tu** servidor.

```
Móvil (Room) ──HTTPS──▶ Caddy ──▶ Servidor Ktor ──▶ PostgreSQL
Navegador (panel) ──────▶   │        (sirve también el panel web)
```

- **Login:** se sigue usando Firebase Auth (gratis). El servidor comprueba la firma de Google del token;
  no necesita ninguna clave de Firebase.
- **Datos:** solo en tu PostgreSQL. Firestore ya no recibe datos de la app.
- **Panel web:** `https://TU_DOMINIO`, vinculado con un código que da la app (solo lectura).

## Requisitos

- VM con Ubuntu y **al menos 2 GB de RAM para construir la imagen** (con 1 GB, añade swap; ver abajo).
  Sirve la Ampere A1 gratuita de Oracle (ARM): todas las imágenes tienen versión arm64.
- Un dominio que apunte a la IP pública de la VM. Gratis: crea un subdominio en [duckdns.org](https://www.duckdns.org).
- Puertos 80 y 443 libres en la VM. Si ya tienes otro proxy (por ejemplo, para n8n), mira la sección del final.

## Pasos

1. **Abrir puertos 80 y 443.**
   - Oracle Cloud: *Networking → Virtual Cloud Networks → tu VCN → Security Lists → Add Ingress Rules*,
     origen `0.0.0.0/0`, TCP, puertos `80` y `443`.
   - Dentro de la VM, las imágenes de Ubuntu de Oracle traen un cortafuegos propio:
     ```bash
     sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
     sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
     sudo netfilter-persistent save
     ```
2. **Instalar Docker:**
   ```bash
   curl -fsSL https://get.docker.com | sudo sh
   sudo usermod -aG docker $USER   # cierra y abre la sesión SSH
   ```
3. **Descargar el código y configurar:**
   ```bash
   git clone https://github.com/xavki/App-de-gym.git && cd App-de-gym/deploy
   cp .env.example .env && nano .env      # DOMAIN, DB_PASSWORD, FIREBASE_PROJECT_ID
   ```
4. **Arrancar** (la primera construcción tarda unos minutos):
   ```bash
   docker compose up -d --build
   curl https://TU_DOMINIO/health           # → {"status":"ok","app":"gymflow"}
   ```
5. **En la app:** *Perfil → Sincronización → Servidor* → `https://TU_DOMINIO` → *Sincronizar*.
   Para el panel: *Vincular panel web*, abre `https://TU_DOMINIO` en el ordenador y escribe el código.
6. **Copias de seguridad:** `crontab -e` y añade `0 4 * * * /home/ubuntu/App-de-gym/deploy/backup.sh`.
   Cada copia es `backups/gymflow-FECHA-HORA.sql.gz`; si `pg_dump` o `gzip` fallan o el volcado queda
   incompleto, el script sale con error, no deja archivo y no borra las anteriores
   (pruebas sin Docker: `bash deploy/test/backup_test.sh`).
   Restaurar: `gunzip -c backups/gymflow-FECHA-HORA.sql.gz | docker compose exec -T db psql -U gymflow gymflow`.

## Actualizar

```bash
cd App-de-gym && git pull && cd deploy && docker compose up -d --build
```

Las migraciones de la base de datos se aplican solas al arrancar el servidor.

## Publicar el módulo de nutrición (migraciones V2 y V3)

Solo añaden tablas (`nutrition_*`); no tocan `users`, `records`, `pairing_codes` ni `panel_tokens`,
y la app Android no cambia. Se aplican solas al arrancar el servidor nuevo (cada una en su transacción).

1. **Guardar la imagen actual** para poder volver sin reconstruir: `docker tag deploy-server:latest deploy-server:pre-nutricion`
2. **Copia verificada** (con `pipefail`: si `pg_dump` falla, el comando falla):
   ```bash
   bash -c 'set -euo pipefail; F=backups/pre-nutricion-$(date +%F-%H%M).sql.gz
     docker compose exec -T db pg_dump -U gymflow gymflow | gzip > "$F"
     gzip -t "$F" && zcat "$F" | tail -n 5 | grep -q "dump complete" && ls -lh "$F"'
   ```
3. **Construir sin parar nada:** `git pull && docker compose build server`
4. **Arrancar el nuevo:** `docker compose up -d server` y `docker compose logs -f server` (debe
   aplicar 2 y 3 y quedar escuchando).
5. **Comprobar:** `/health`, sincronizar desde la app y la pestaña Nutrición.

**Volver atrás (de menos a más drástico):**
- *Solo el servidor (preferente, no toca datos):*
  `docker tag deploy-server:pre-nutricion deploy-server:latest && docker compose up -d --no-build --force-recreate server`.
  Arranca aunque la base tenga V2/V3 (Flyway ignora migraciones "futuras"); las tablas de nutrición quedan sin usar.
- *Quitar también nutrición de la base* (pierde solo los registros de nutrición):
  ```sql
  DROP TABLE nutrition_goal_history, nutrition_logs, nutrition_goals, nutrition_meals;
  DELETE FROM flyway_schema_history WHERE version IN ('2', '3');
  ```
- *Restaurar la copia completa* (último recurso: pierde lo sincronizado después de la copia; antes,
  haz otra copia del estado actual).

## VM con 1 GB de RAM

Construir la imagen (Gradle + npm) necesita más memoria. Añade 2 GB de swap:

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

## Si los puertos 80/443 ya están ocupados

Si otro servicio (n8n, nginx…) ya usa esos puertos, quita el servicio `caddy` de `docker-compose.yml`,
publica el servidor solo en local (`ports: ["127.0.0.1:8081:8080"]`) y añade un sitio en tu proxy
actual que apunte a `http://127.0.0.1:8081`.

## Probar en local (sin VM)

```bash
cd server && ./gradlew test       # tests del servidor con PostgreSQL embebido
cd server && ./gradlew runDev     # servidor de desarrollo en http://localhost:8080
```

`runDev` usa PostgreSQL embebido y claves de prueba, no las de Firebase. Solo acepta tokens de
`/dev/token` y lo usan los tests de extremo a extremo (`app/src/androidTest/.../EndToEndTest.kt`).
El emulador lo ve en `http://10.0.2.2:8080`; esa dirección sin HTTPS solo se permite en builds de depuración.
