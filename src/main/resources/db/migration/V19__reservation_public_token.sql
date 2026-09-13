-- Les routes publiques (lecture, replanification) ne se fondent plus sur l'identifiant
-- de la reservation, visible de tous cote back-office, mais sur un jeton dedie que seul
-- le client recoit dans son message de confirmation.
ALTER TABLE reservations ADD COLUMN public_token UUID NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX uq_reservations_public_token ON reservations (public_token);
