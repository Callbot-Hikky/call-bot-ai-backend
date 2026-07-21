-- Seed de developpement : garantit l'organisation, le restaurant et le compte
-- utilises par le frontend (restaurantId code en dur, auto-login dev). Sans ce
-- seed, une base fraichement migree renvoie 404 sur toute creation.
-- Idempotent (ON CONFLICT DO NOTHING) : aucune donnee existante n'est modifiee.

INSERT INTO organizations (id, name, created_at, updated_at)
VALUES ('80868caf-efac-472f-b46e-54da6c402907', 'hassan@callbot.local', now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO restaurants (
    id, organization_id, name, phone_number, timezone, locale, settings, is_active,
    created_at, updated_at)
VALUES (
    '22b60047-3341-4b71-bed8-e22bc08c3603',
    '80868caf-efac-472f-b46e-54da6c402907',
    'Le Bistrot du Coin', '+33100000000', 'Europe/Paris', 'fr', '{}', true,
    now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO users (
    id, organization_id, email, password_hash, role, created_at, updated_at)
VALUES (
    'c8919a50-c335-4135-89f1-0233a49a3928',
    '80868caf-efac-472f-b46e-54da6c402907',
    'hassan@callbot.local',
    '$2a$10$KOUUUId3DjWODohLqwg2M.OktD9cZ/AGwM3yoQ9vycbb1BVQ4YphC',
    'OWNER', now(), now())
ON CONFLICT (id) DO NOTHING;
