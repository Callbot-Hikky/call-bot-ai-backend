# Call Bot AI Backend

Backend Spring Boot du Call Bot AI : API REST stateless avec authentification JWT.

## Stack

- **Java 21** (LTS)
- **Spring Boot 4.1** — Web (MVC), Data JPA, Validation, Actuator, Security
- **MySQL 8.4**
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
├── controller/                        # AuthController · MeController · PingController
├── service/                           # AuthService (logique métier)
├── repository/                        # UserRepository (accès données)
├── model/                             # User · Role (entités JPA)
├── dto/                               # RegisterRequest · LoginRequest · AuthResponse · ApiError
├── exception/                         # EmailAlreadyUsedException · GlobalExceptionHandler
├── security/                          # JwtService · JwtAuthenticationFilter · AppUserDetailsService
└── config/                            # SecurityConfig · JwtProperties
src/main/resources
├── application.yml                    # Configuration (pilotée par variables d'env)
└── db/migration/                      # Migrations Flyway (V1__, V2__, ...)
```

Flux d'une requête : `controller → service → repository → model`.
`security` + `config` portent la configuration transverse (JWT, Spring Security).

## Démarrer avec Docker (recommandé)

Tout (app + base MySQL) tourne dans Docker, rien à installer en local hormis Docker.

```bash
cp .env.example .env        # adapter les valeurs si besoin
docker compose up --build
```

L'API est disponible sur http://localhost:8080

- Sanity check : `GET http://localhost:8080/api/ping`
- Santé : `GET http://localhost:8080/actuator/health`

Arrêter : `docker compose down` (ajouter `-v` pour effacer les données MySQL).

## Démarrer en local (sans Docker)

Nécessite un JDK 21 et une instance MySQL accessible. La configuration se fait
via les variables d'environnement (valeurs par défaut dans `application.yml`) :

```bash
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/callbot?createDatabaseIfNotExist=true"
export SPRING_DATASOURCE_USERNAME=callbot
export SPRING_DATASOURCE_PASSWORD=callbot

./mvnw spring-boot:run
```

## API

| Méthode | Endpoint             | Auth   | Description                                  |
|---------|----------------------|--------|----------------------------------------------|
| `POST`  | `/api/auth/register` | Non    | Crée un compte, renvoie un token JWT (201)   |
| `POST`  | `/api/auth/login`    | Non    | Authentifie, renvoie un token JWT (200)      |
| `GET`   | `/api/me`            | Bearer | Renvoie l'utilisateur courant                |
| `GET`   | `/api/ping`          | Non    | Sanity check                                 |
| `GET`   | `/actuator/health`   | Non    | État de santé                                |

Codes d'erreur : `400` validation (avec détail par champ), `401` identifiants
invalides ou token manquant/invalide, `409` email déjà utilisé.

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
| `SPRING_DATASOURCE_URL`      | `jdbc:mysql://localhost:3306/…`| URL JDBC MySQL                       |
| `SPRING_DATASOURCE_USERNAME` | `callbot`                      | Utilisateur DB                       |
| `SPRING_DATASOURCE_PASSWORD` | `callbot`                      | Mot de passe DB                      |
| `JWT_SECRET`                 | valeur de dev                  | Secret de signature (min. 32 octets) |
| `JWT_EXPIRATION_MS`          | `86400000` (24 h)              | Durée de validité du token           |
| `SERVER_PORT`                | `8080`                         | Port HTTP                            |

> ⚠️ **En production**, remplace impérativement `JWT_SECRET` par une valeur
> aléatoire longue et garde-la hors du dépôt.

## Tests

La suite couvre trois niveaux : unitaire (Mockito), web slice (`@WebMvcTest`)
et intégration sur un **vrai MySQL** via Testcontainers (nécessite Docker).

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
