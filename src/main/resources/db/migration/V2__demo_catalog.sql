INSERT INTO events
VALUES ('10000000-0000-0000-0000-000000000001', 'organizer', 'Midnight Sessions', 'Harbor Hall', 'PUBLISHED',
        clock_timestamp());
INSERT INTO occurrences(id, event_id, starts_at)
VALUES ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        clock_timestamp() + interval '30 days');
INSERT INTO inventory(id, occurrence_id, section, label, category, price)
SELECT gen_random_uuid(),
       '20000000-0000-0000-0000-000000000001',
       CASE WHEN n <= 30 THEN 'Front' ELSE 'Balcony' END,
       chr(65 + ((n - 1) / 10)) || (((n - 1)%10)+1), 'RESERVED', CASE WHEN n<=30 THEN 250000 ELSE 150000 END
FROM generate_series(1, 60) n;
INSERT INTO inventory(id, occurrence_id, section, label, category, price)
SELECT gen_random_uuid(), '20000000-0000-0000-0000-000000000001', 'Standing', 'GA-' || n, 'GA', 75000
FROM generate_series(1, 20) n;
