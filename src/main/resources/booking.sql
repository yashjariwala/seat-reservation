-- One server-side call for all booking gates. The caller still controls COMMIT;
-- locks survive this function until the surrounding transaction finishes.
CREATE OR REPLACE FUNCTION public.reserve_booking(
    p_id uuid, p_show uuid, p_user text, p_key text,
    p_seats text[], p_amount bigint, p_limit integer
) RETURNS TABLE (
    result_outcome text, result_id uuid, result_show uuid, result_user text,
    result_seats text[], result_amount bigint, result_status text
) LANGUAGE plpgsql VOLATILE SET search_path = public, pg_temp AS $$
DECLARE
    previous reservations%ROWTYPE;
    wanted integer := cardinality(p_seats);
    matched integer;
BEGIN
    -- This block rolls back every write on our private domain SQLSTATEs.
    -- Unexpected database/trigger failures are deliberately not swallowed.
    BEGIN
        INSERT INTO reservations (id, show_id, user_id, idem_key, seats, amount_paise, status)
        VALUES (p_id, p_show, p_user, p_key, p_seats, p_amount, 'confirmed')
        ON CONFLICT (user_id, idem_key) DO NOTHING;
        IF NOT FOUND THEN
            -- VOLATILE gets a fresh statement snapshot after a conflicting inserter commits.
            SELECT r.* INTO STRICT previous FROM reservations r
            WHERE r.user_id = p_user AND r.idem_key = p_key;
            IF previous.show_id <> p_show OR previous.seats <> p_seats THEN
                RETURN QUERY SELECT 'idempotency_key_reused'::text, NULL::uuid, NULL::uuid,
                    NULL::text, NULL::text[], NULL::bigint, NULL::text;
            ELSE
                RETURN QUERY SELECT 'idempotent_replay'::text, previous.id, previous.show_id,
                    previous.user_id, previous.seats, previous.amount_paise, previous.status;
            END IF;
            RETURN;
        END IF;

        IF wanted > p_limit THEN
            RAISE EXCEPTION USING ERRCODE = 'P2002', MESSAGE = 'per_user_limit';
        END IF;
        INSERT INTO user_counts (show_id, user_id, seat_count) VALUES (p_show, p_user, wanted)
        ON CONFLICT (show_id, user_id) DO UPDATE
        SET seat_count = user_counts.seat_count + EXCLUDED.seat_count
        WHERE user_counts.seat_count + EXCLUDED.seat_count <= p_limit;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = 'P2002', MESSAGE = 'per_user_limit';
        END IF;

        PERFORM s.label FROM seats s
        WHERE s.show_id = p_show AND s.label = ANY(p_seats)
        ORDER BY s.label FOR UPDATE;
        GET DIAGNOSTICS matched = ROW_COUNT;
        IF matched <> wanted THEN
            RAISE EXCEPTION USING ERRCODE = 'P2003', MESSAGE = 'unknown_seats';
        END IF;
        UPDATE seats s SET status = 'confirmed', reservation_id = p_id
        WHERE s.show_id = p_show AND s.label = ANY(p_seats) AND s.status = 'available';
        GET DIAGNOSTICS matched = ROW_COUNT;
        IF matched <> wanted THEN
            RAISE EXCEPTION USING ERRCODE = 'P2001', MESSAGE = 'seat_taken';
        END IF;
        RETURN QUERY SELECT 'confirmed'::text, p_id, p_show, p_user, p_seats, p_amount, 'confirmed'::text;
    EXCEPTION WHEN SQLSTATE 'P2001' OR SQLSTATE 'P2002' OR SQLSTATE 'P2003' THEN
        RETURN QUERY SELECT CASE SQLSTATE
            WHEN 'P2001' THEN 'seat_taken'
            WHEN 'P2002' THEN 'per_user_limit'
            ELSE 'unknown_seats' END,
            NULL::uuid, NULL::uuid, NULL::text, NULL::text[], NULL::bigint, NULL::text;
    END;
END;
$$;
