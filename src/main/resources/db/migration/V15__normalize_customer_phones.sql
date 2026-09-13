-- Les numeros de telephone sont desormais normalises a l'ecriture (sans espaces, points,
-- parentheses ni tirets). On aligne l'existant, sauf quand la forme normalisee existe
-- deja pour le meme restaurant : ces doublons sont laisses tels quels pour ne pas
-- violer uq_customers_restaurant_phone, et seront fusionnes a la main.
UPDATE customers c
SET phone = regexp_replace(c.phone, '[[:space:].()-]', '', 'g')
WHERE c.phone <> regexp_replace(c.phone, '[[:space:].()-]', '', 'g')
  AND NOT EXISTS (
      SELECT 1 FROM customers d
      WHERE d.restaurant_id = c.restaurant_id
        AND d.id <> c.id
        AND d.phone = regexp_replace(c.phone, '[[:space:].()-]', '', 'g')
  );
