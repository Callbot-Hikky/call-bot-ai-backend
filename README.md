# Call Bot AI Backend

Backend Spring Boot du Call Bot AI.

## Stack

- **Java 21** (LTS)
- **Spring Boot 4.1** — Web (MVC), Data JPA, Validation, Actuator
- **MySQL 8.4**
- **Flyway** pour les migrations de base de données
- **Maven** (via wrapper `./mvnw`)
- **Docker / Docker Compose**

## Structure du projet

```
src/main/java/com/callbot/ai
├── CallBotAiBackendApplication.java   # Point d'entrée
└── web/                               # Contrôleurs REST
src/main/resources
├── application.yml                    # Configuration (pilotée par variables d'env)
└── db/migration/                      # Migrations Flyway (V1__, V2__, ...)
```

Convention de packages à suivre au fur et à mesure :
`web` (controllers) · `service` (logique métier) · `domain` (entités) · `repository` (accès données) · `config`.

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

## Tests

```bash
./mvnw test
```

## Migrations de base de données

Le schéma est géré **exclusivement par Flyway** (`ddl-auto: validate` côté Hibernate).
Pour modifier le schéma, ajouter un nouveau fichier dans `src/main/resources/db/migration`
nommé `V<n>__description.sql`. Ne jamais modifier une migration déjà appliquée.
