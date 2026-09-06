# Call Bot AI Backend

Backend Spring Boot du Call Bot AI : API REST stateless qui gère les restaurants,
les clients et les réservations, et qui reçoit les réservations créées
automatiquement par le bot téléphonique IA. Authentification JWT pour les
utilisateurs, clé d'API de service pour l'IA.

## Stack

- **Java 21** (LTS)
- **Spring Boot 4.1** — Web (MVC), Data JPA, Validation, Actuator, Security
- **PostgreSQL 16**
- **Flyway** pour les migrations de base de données
- **JWT** (jjwt) pour l'authentification stateless, mots de passe hashés en **BCrypt**
- **Maven** (via wrapper `./mvnw`)
- **Docker / Docker Compose**
- **JUnit 5 · Mockito · Testcontainers** pour les tests

## Structure du projet

Organisation **en couches** (package by layer) : chaque package regroupe les
classes par rôle technique.

```
src/main/java/com/callbot/ai
├── CallBotAiBackendApplication.java   # Point d'entrée
├── controller/                        # Auth · Me · Restaurant · RestaurantTable · RestaurantHours
│                                      #   · Customer · Reservation · CallIngest · Ping
├── service/                           # Logique métier (dont CallIngestService)
├── repository/                        # Accès données (Spring Data JPA)
├── model/                             # Entités JPA (Organization · User · Restaurant · Table
│                                      #   · Customer · Call · Reservation ...)
├── dto/                               # Objets de requête/réponse de l'API
├── exception/                         # Exceptions métier · GlobalExceptionHandler
├── security/                          # JWT (utilisateurs) · clé d'API de service (IA)
└── config/                            # SecurityConfig · JwtProperties · ServiceProperties
src/main/resources
├── application.yml                    # Configuration (pilotée par variables d'env)
└── db/migration/                      # Migrations Flyway (V1__, V2__, ...)
```

Flux d'une requête : `controller → service → repository → model`.
`security` + `config` portent la configuration transverse (JWT, Spring Security).

## Démarrer avec Docker (recommandé)

Tout (app + base PostgreSQL) tourne dans Docker, rien à installer en local hormis Docker.

```bash
cp .env.example .env        # adapter les valeurs si besoin
docker compose up --build
```

L'API est disponible sur http://localhost:8080

- Sanity check : `GET http://localhost:8080/api/ping`
- Santé : `GET http://localhost:8080/actuator/health`

Arrêter : `docker compose down` (ajouter `-v` pour effacer les données PostgreSQL).

> Avec `docker-compose.yml`, l'image embarque un jar figé : **chaque** changement de
> code impose un `docker compose up --build`. Pour du dev itératif, préfère le mode
> hot-reload ci-dessous.

## Développement avec hot-reload automatique

`docker-compose.dev.yml` monte le code source et lance l'app via Maven. Un watcher
(`inotify`) recompile à chaque sauvegarde de fichier, et **Spring Boot DevTools**
redémarre l'app. **Tu ne lances rien après une modif : tu sauvegardes, c'est tout.**

```bash
# Démarrer la stack de dev (app + PostgreSQL) — le 1er run build l'image dev
docker compose -f docker-compose.dev.yml up
```

Édite ton code, sauvegarde → l'app est à jour en ~1-2 s. On passe d'un cycle de
~40 s (rebuild d'image) à quelques secondes, sans aucune commande manuelle.

Détails :
- Le premier démarrage build l'image dev (`Dockerfile.dev`, contient JDK + inotify)
  et télécharge les dépendances Maven (mises en cache dans un volume). Ce build est
  ponctuel : il ne se relance pas à chaque modif de code.
- `target/` vit dans un volume Docker dédié (ton `target/` hôte n'est pas pollué).

Réserve `docker-compose.yml` (jar packagé) à la prod / CI.

## Démarrer en local (sans Docker)

Nécessite un JDK 21 et une instance PostgreSQL accessible. La configuration se fait
via les variables d'environnement (valeurs par défaut dans `application.yml`) :

```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/callbot"
export SPRING_DATASOURCE_USERNAME=callbot
export SPRING_DATASOURCE_PASSWORD=callbot

./mvnw spring-boot:run
```

## API

L'API distingue **deux types d'appelants** :
- les **utilisateurs** (staff du restaurant), authentifiés par un **token JWT** (`Authorization: Bearer …`) ;
- le **microservice IA**, authentifié par une **clé d'API de service** (`X-Api-Key`), réservée à l'ingestion d'appels.

### Authentification & compte

| Méthode | Endpoint             | Auth   | Description                                  |
|---------|----------------------|--------|----------------------------------------------|
| `POST`  | `/api/auth/register` | Non    | Crée un compte, renvoie un token JWT (201)   |
| `POST`  | `/api/auth/login`    | Non    | Authentifie, renvoie un token JWT (200)      |
| `GET`   | `/api/me`            | Bearer | Utilisateur courant (id, email, `organizationId`, rôle) |

### Ressources métier (CRUD, Bearer)

Chaque ressource expose le CRUD complet : `POST` (201), `GET` liste, `GET /{id}`,
`PUT /{id}`, `DELETE /{id}` (204).

| Ressource          | Base path                | Filtre de liste            |
|--------------------|--------------------------|----------------------------|
| Restaurants        | `/api/restaurants`       | `?organizationId=`         |
| Tables             | `/api/tables`            | `?restaurantId=`           |
| Horaires           | `/api/restaurant-hours`  | `?restaurantId=`           |
| Clients            | `/api/customers`         | `?restaurantId=`           |
| Réservations       | `/api/reservations`      | `?restaurantId=`           |

**Expansion à la demande** sur les réservations : `?expand=table,customer` enrichit
la réponse avec les ressources liées en **un seul appel**, au lieu d'obliger le
front à enchaîner plusieurs requêtes. Ex. `GET /api/reservations/{id}?expand=table,customer`.

### Ingestion d'appel IA (clé d'API de service)

| Méthode | Endpoint            | Auth        | Description                                       |
|---------|---------------------|-------------|--------------------------------------------------|
| `POST`  | `/api/calls/ingest` | `X-Api-Key` | Crée client + appel + réservation (201), idempotent |

C'est le **point d'entrée du bot IA** : à la fin d'un appel, le microservice y
envoie la réservation captée. Le backend, en **une seule transaction** :

```
Appel terminé (bot IA)
        │  POST /api/calls/ingest  (X-Api-Key)
        ▼
1. Retrouve le restaurant via restaurantPhone (numéro appelé)   ── inconnu ─▶ 404
2. Retrouve ou crée le client (clé : restaurant + téléphone)
3. Enregistre l'appel (idempotent sur twilioCallSid)
4. Crée la réservation (source = "callbot")
        ▼
201 { callId, customerId, reservation, alreadyProcessed }
```

**Idempotence** : si le même appel (`twilioCallSid`) est renvoyé, rien n'est
recréé — la réservation existante est renvoyée avec `alreadyProcessed = true`.
Cela protège contre les doublons en cas de renvoi réseau.

Exemple de payload envoyé par l'IA :

```json
{
  "twilioCallSid": "CA-abc123",
  "restaurantPhone": "+33611112222",
  "fromNumber": "+33700000000",
  "customer": { "phone": "+33700000000", "firstName": "Alice" },
  "reservation": {
    "startsAt": "2030-03-01T19:00:00Z",
    "endsAt": "2030-03-01T21:00:00Z",
    "partySize": 2,
    "notes": "Près de la fenêtre"
  }
}
```

### Offres & paiement (Bearer)

| Méthode | Endpoint                         | Auth | Description                                  |
|---------|----------------------------------|------|----------------------------------------------|
| `GET`   | `/api/offers`                    | Oui  | Catalogue des plans (prix côté serveur)      |
| `POST`  | `/api/offers/{code}/checkout`    | Oui  | Ouvre un checkout, renvoie l'URL de paiement |
| `GET`   | `/api/offers/checkout/{id}`      | Oui  | Récap d'une session (page de succès)         |
| `POST`  | `/api/offers/webhook`            | Non  | Callback du prestataire (signature vérifiée) |

### Divers

| Méthode | Endpoint           | Auth | Description   |
|---------|--------------------|------|---------------|
| `GET`   | `/api/ping`        | Non  | Sanity check  |
| `GET`   | `/actuator/health` | Non  | État de santé |

Codes d'erreur : `400` validation (avec détail par champ), `401` identifiants
invalides / token ou clé d'API manquant·e ou invalide, `403` accès interdit,
`404` ressource introuvable, `409` email déjà utilisé.

### Exemple

```bash
# Inscription -> renvoie { "accessToken": "...", "tokenType": "Bearer" }
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"password123"}'

# Accès à un endpoint protégé
curl http://localhost:8080/api/me \
  -H "Authorization: Bearer <accessToken>"
```

## Configuration

Variables d'environnement principales (valeurs par défaut dans `application.yml`,
exemples dans `.env.example`) :

| Variable                     | Défaut (dev)                   | Description                          |
|------------------------------|--------------------------------|--------------------------------------|
| `SPRING_DATASOURCE_URL`      | `jdbc:postgresql://localhost:5432/…`| URL JDBC PostgreSQL             |
| `SPRING_DATASOURCE_USERNAME` | `callbot`                      | Utilisateur DB                       |
| `SPRING_DATASOURCE_PASSWORD` | `callbot`                      | Mot de passe DB                      |
| `JWT_SECRET`                 | valeur de dev                  | Secret de signature (min. 32 octets) |
| `JWT_EXPIRATION_MS`          | `86400000` (24 h)              | Durée de validité du token           |
| `SERVER_PORT`                | `8080`                         | Port HTTP                            |

> ⚠️ **En production**, remplace impérativement `JWT_SECRET` par une valeur
> aléatoire longue et garde-la hors du dépôt.

### Paiement

| Variable                | Défaut (dev)                          | Description                                     |
|-------------------------|---------------------------------------|-------------------------------------------------|
| `PAYMENT_PROVIDER`      | `stripe`                              | Adapter de paiement activé                      |
| `STRIPE_SECRET_KEY`     | vide                                  | Clé secrète ; vide = checkout désactivé (`502`) |
| `STRIPE_PRO_PRICE_ID`   | vide                                  | Price récurrent de l'offre `pro` ; vide = prix inline |
| `STRIPE_WEBHOOK_SECRET` | vide                                  | Secret de signature (`whsec_…`) des webhooks    |
| `STRIPE_SUCCESS_URL`    | `http://localhost:4200/offre/success` | Page de retour après paiement                   |
| `STRIPE_CANCEL_URL`     | `http://localhost:4200/offre`         | Page de retour si abandon                       |

#### Changer de prestataire

Le domaine ne connaît que deux ports, dans `com.callbot.ai.gateway` :
`PaymentGateway` (ouvrir un checkout) et `PaymentEventParser` (authentifier et
normaliser un webhook). Stripe n'est qu'un adapter, confiné à
`gateway/stripe/` — c'est le **seul** package qui importe `com.stripe.*`.

Pour brancher un autre prestataire :

1. créer `gateway/<provider>/` avec les deux implémentations, annotées
   `@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = "<provider>")` ;
2. ajouter ses `@ConfigurationProperties` dans ce même package ;
3. poser `PAYMENT_PROVIDER=<provider>`.

Aucun changement dans `OfferService`, les contrôleurs, les entités ou le front :
le catalogue, les statuts d'abonnement et le contrat HTTP sont inchangés. La
colonne `provider` garde la trace du prestataire ayant créé chaque abonnement.

## Tests

La suite couvre trois niveaux : unitaire (Mockito), web slice (`@WebMvcTest`)
et intégration sur un **vrai PostgreSQL** via Testcontainers (nécessite Docker).

Avec un JDK 21 installé :

```bash
./mvnw test
```

Sans JDK local (exécution dans un conteneur, avec accès au démon Docker pour
Testcontainers) :

```bash
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --add-host=host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -v "$PWD":/app -v callbot-m2:/root/.m2 -w /app \
  eclipse-temurin:21-jdk-jammy ./mvnw -B test
```

## Migrations de base de données

Le schéma est géré **exclusivement par Flyway** (`ddl-auto: validate` côté Hibernate).
Pour modifier le schéma, ajouter un nouveau fichier dans `src/main/resources/db/migration`
nommé `V<n>__description.sql`. Ne jamais modifier une migration déjà appliquée
(le checksum ne correspondrait plus et Flyway refuserait de démarrer).
