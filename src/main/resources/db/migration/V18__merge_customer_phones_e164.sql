-- Les numeros francais sont ecrits sous leur forme internationale (+33...), comme les
-- numeros recus de l'operateur par l'assistant vocal : une seule fiche par client.
-- Quand la forme internationale existe deja pour le meme restaurant, les reservations et
-- les appels de la fiche nationale sont rattaches a la fiche internationale, puis la
-- fiche nationale est supprimee : plus de doublon introuvable.
WITH canon AS (
    SELECT c.id,
           c.restaurant_id,
           CASE
               WHEN c.phone ~ '^00' THEN '+' || substr(c.phone, 3)
               WHEN c.phone ~ '^0[1-9][0-9]{8}$' THEN '+33' || substr(c.phone, 2)
               ELSE c.phone
           END AS phone
    FROM customers c
),
dupes AS (
    SELECT old.id AS old_id, keep.id AS keep_id
    FROM canon old
    JOIN customers keep ON keep.restaurant_id = old.restaurant_id AND keep.phone = old.phone
    WHERE keep.id <> old.id
)
UPDATE reservations r
SET customer_id = d.keep_id
FROM dupes d
WHERE r.customer_id = d.old_id;

WITH canon AS (
    SELECT c.id,
           c.restaurant_id,
           CASE
               WHEN c.phone ~ '^00' THEN '+' || substr(c.phone, 3)
               WHEN c.phone ~ '^0[1-9][0-9]{8}$' THEN '+33' || substr(c.phone, 2)
               ELSE c.phone
           END AS phone
    FROM customers c
),
dupes AS (
    SELECT old.id AS old_id, keep.id AS keep_id
    FROM canon old
    JOIN customers keep ON keep.restaurant_id = old.restaurant_id AND keep.phone = old.phone
    WHERE keep.id <> old.id
)
UPDATE calls ca
SET customer_id = d.keep_id
FROM dupes d
WHERE ca.customer_id = d.old_id;

DELETE FROM customers c
USING (
    SELECT old.id AS old_id
    FROM (
        SELECT id, restaurant_id,
               CASE
                   WHEN phone ~ '^00' THEN '+' || substr(phone, 3)
                   WHEN phone ~ '^0[1-9][0-9]{8}$' THEN '+33' || substr(phone, 2)
                   ELSE phone
               END AS phone
        FROM customers
    ) old
    JOIN customers keep ON keep.restaurant_id = old.restaurant_id AND keep.phone = old.phone
    WHERE keep.id <> old.id
) d
WHERE c.id = d.old_id;

UPDATE customers
SET phone = CASE
                WHEN phone ~ '^00' THEN '+' || substr(phone, 3)
                WHEN phone ~ '^0[1-9][0-9]{8}$' THEN '+33' || substr(phone, 2)
                ELSE phone
            END
WHERE phone ~ '^00' OR phone ~ '^0[1-9][0-9]{8}$';
