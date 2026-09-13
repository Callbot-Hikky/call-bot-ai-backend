-- Modification par le convive : couverts et horaire, sans passer par le restaurant.
--
-- Le convive n'avait jusqu'ici qu'un lien d'annulation. Le message de confirmation
-- proposait bien « Choisissez un autre horaire », mais l'adresse pointait sur l'UUID
-- brut de la réservation et sur une page qui n'a jamais existé : un lien mort, et une
-- adresse devinable par quiconque connaît un identifiant.

-- 1. Le jeton de modification ---------------------------------------------------
--
-- Même nature que le jeton d'annulation — 32 octets aléatoires, seule identification
-- d'un convive sans compte — avec une différence : il n'est PAS à usage unique. Une
-- tablée qui passe de 4 à 6 puis de 6 à 5 emprunte deux fois le même lien ; le
-- consommer au premier passage obligerait à en renvoyer un à chaque modification.

ALTER TABLE reservations
    ADD COLUMN modification_token VARCHAR(64);

COMMENT ON COLUMN reservations.modification_token IS
    'Lien de modification du convive. Réutilisable, valable jusqu''à l''échéance de modification.';

-- Reprise : les réservations vivantes en reçoivent un, sans quoi le lien du message
-- déjà envoyé resterait mort pour elles. 64 caractères hexadécimaux = 256 bits, la
-- même entropie que les jetons émis par l'application.
UPDATE reservations
SET modification_token = replace(gen_random_uuid()::text, '-', '')
                      || replace(gen_random_uuid()::text, '-', '')
WHERE status <> 'cancelled';

-- Le jeton est la seule identification du convive : il doit désigner une réservation
-- et une seule.
CREATE UNIQUE INDEX idx_reservations_modification_token
    ON reservations (modification_token) WHERE modification_token IS NOT NULL;

-- 2. L'échéance de modification -------------------------------------------------
--
-- Fixée par le restaurateur, en heures avant le service, comme la fenêtre de
-- remboursement dont elle reprend l'unité et la sémantique : zéro signifie « jusqu'au
-- service », jamais « jamais ». Les deux échéances restent indépendantes — rendre
-- l'argent et changer une tablée n'engagent pas la salle de la même façon.
--
-- Zéro par défaut : la modification est le service rendu par ce ticket, la fermer
-- d'office la rendrait invisible. Un restaurateur qui veut fermer ses deux dernières
-- heures le posera.

ALTER TABLE restaurants
    ADD COLUMN modification_window_hours INTEGER NOT NULL DEFAULT 0;

ALTER TABLE restaurants
    ADD CONSTRAINT chk_restaurants_modification_window
        CHECK (modification_window_hours >= 0);

-- Gelée sur la réservation à sa création, comme le mode, le prix par couvert et la
-- fenêtre de remboursement : un restaurateur qui resserre son réglage ne retire pas
-- une promesse déjà faite.
ALTER TABLE reservations
    ADD COLUMN modification_window_hours INTEGER;

ALTER TABLE reservations
    ADD CONSTRAINT chk_reservations_modification_window
        CHECK (modification_window_hours IS NULL OR modification_window_hours >= 0);

COMMENT ON COLUMN reservations.modification_window_hours IS
    'Heures avant le service au-delà desquelles la modification ferme. Figée à la création.';

-- 3. Le remboursement partiel ---------------------------------------------------
--
-- Une baisse de couverts rend désormais (N − M) × le prix par couvert figé, là où la
-- décision d'origine ne rendait rien. L'encaissement reste `paid` : le reste appartient
-- toujours au restaurateur et lui sera reversé. Seul `refunded_amount_cents` bouge, et
-- le reversement doit le déduire.
--
-- `refunded_at` cesse donc de signifier « clos » pour signifier « dernier mouvement de
-- retour ». La distinction se lit sur le statut, pas sur cette date.
COMMENT ON COLUMN reservation_charges.refunded_amount_cents IS
    'Total rendu au convive sur cet encaissement. Égal au montant si `refunded`, partiel si `paid`.';
