-- Usuarios: el id es el uid de Firebase Auth.
-- sync_version es un contador por usuario: cada registro que cambia recibe el
-- siguiente número, y el cliente pide "todo lo posterior a mi cursor".
CREATE TABLE users (
    id            TEXT PRIMARY KEY,
    email         TEXT,
    sync_version  BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ
);

-- Almacén genérico: una fila por registro de cualquier tabla de la app.
-- El contenido va en JSONB tal cual lo manda el móvil, así el servidor no
-- necesita migraciones cada vez que la app añade un campo.
-- La clave incluye user_id: cada usuario tiene su propio espacio de ids.
CREATE TABLE records (
    user_id     TEXT   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    table_name  TEXT   NOT NULL,
    id          TEXT   NOT NULL,
    updated_at  BIGINT NOT NULL,
    deleted_at  BIGINT,
    version     BIGINT NOT NULL,
    data        JSONB  NOT NULL,
    PRIMARY KEY (user_id, table_name, id)
);
CREATE INDEX records_user_version ON records (user_id, version);

-- Códigos de un solo uso (10 min) para vincular el panel web desde la app.
CREATE TABLE pairing_codes (
    code        TEXT PRIMARY KEY,
    user_id     TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL
);

-- Tokens de solo lectura del panel web. Se guarda el hash, nunca el token.
CREATE TABLE panel_tokens (
    token_hash    TEXT PRIMARY KEY,
    user_id       TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ
);
CREATE INDEX panel_tokens_user ON panel_tokens (user_id);
