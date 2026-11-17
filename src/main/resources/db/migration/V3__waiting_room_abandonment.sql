ALTER TABLE waiting_entries
    ADD COLUMN queue_deadline timestamptz NOT NULL DEFAULT clock_timestamp() + interval '5 minutes';
