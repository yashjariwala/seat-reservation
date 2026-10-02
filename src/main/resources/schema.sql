-- Idempotent: runs on every boot (spring.sql.init.mode=always).

CREATE TABLE IF NOT EXISTS shows (
    id             UUID PRIMARY KEY,
    name           TEXT   NOT NULL,
    price_paise    BIGINT NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT    NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per seat. The row itself is the lock: every state change is a
-- conditional UPDATE guarded on the current status.
CREATE TABLE IF NOT EXISTS seats (
    show_id        UUID NOT NULL REFERENCES shows(id),
    label          TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'available' CHECK (status IN ('available','held','confirmed')),
    reservation_id UUID,
    PRIMARY KEY (show_id, label)
);

-- Cancel frees seats WHERE reservation_id = ?; without this it scans the show's seats.
CREATE INDEX IF NOT EXISTS seats_reservation_id ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

-- UNIQUE(user_id, idem_key) is the exactly-once guarantee for retries.
CREATE TABLE IF NOT EXISTS reservations (
    id           UUID PRIMARY KEY,
    show_id      UUID   NOT NULL REFERENCES shows(id),
    user_id      TEXT   NOT NULL,
    idem_key     TEXT   NOT NULL,
    request_hash TEXT   NOT NULL,
    seats        TEXT[] NOT NULL,
    amount_paise BIGINT NOT NULL,
    status       TEXT   NOT NULL CHECK (status IN ('pending','confirmed','cancelled')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, idem_key)
);

-- Replay now compares show_id + seats directly; request_hash is a legacy column, no longer written.
ALTER TABLE reservations ALTER COLUMN request_hash DROP NOT NULL;

-- Per-user seat counter; the limit check is a single guarded UPDATE on this row.
CREATE TABLE IF NOT EXISTS user_counts (
    show_id    UUID NOT NULL REFERENCES shows(id),
    user_id    TEXT NOT NULL,
    seat_count INT  NOT NULL DEFAULT 0 CHECK (seat_count >= 0),
    PRIMARY KEY (show_id, user_id)
);

-- Seat count as declared at creation: the independent side of the reconciliation check.
ALTER TABLE shows ADD COLUMN IF NOT EXISTS total_seats INT;
UPDATE shows s SET total_seats = (SELECT count(*) FROM seats WHERE show_id = s.id) WHERE total_seats IS NULL;
