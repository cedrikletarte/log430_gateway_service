# API Gateway — Guide d'exploitation

## Présentation

L'API Gateway centralise l'accès aux microservices (Auth, Wallet, Market, Order) et assure :
- Routage HTTP via Traefik (reverse proxy, auto-discovery Docker)
- Authentification JWT au périmètre (extraction/validation du token)
- Propagation sécurisée d'identité aux microservices via en-têtes signés
- Observabilité (health, métriques Prometheus)

Le Gateway est composé de deux conteneurs dans `docker-compose.yml` :
- `traefik` (port 80 et dashboard 8080)
- `gateway-service` (Spring Cloud Gateway exposé derrière Traefik)

**Voir les autres fichiers README pour la configuration des autres microservices**

## Prérequis
- Docker Desktop installé (Windows/macOS) ou Docker Engine (Linux)
- Réseau Docker externe `brokerx-network` (partagé avec les autres services)

Créer le réseau si nécessaire:
```powershell
docker network create brokerx-network
```

## Configuration (.env)
Certaines variables doivent être définies avant le lancement (via un fichier `.env` à la racine de `gateway_service/`) :

| Variable       | Description                                     | Exemple           |
|----------------|--------------------------------------------------|-------------------|
| JWT_SECRET     | Secret utilisé pour signer/valider les JWT       | changeme-secret   |
| GATEWAY_SECRET | Secret pour signer les en-têtes envoyés au back  | changeme-secret   |

Ces variables sont injectées automatiquement dans `gateway-service` via le `docker-compose.yml`.

Important:
- `GATEWAY_SECRET` DOIT être le même que celui attendu par chaque microservice (ils valident la signature via `X-Gateway-Secret`).
- `JWT_SECRET` doit correspondre au secret utilisé par le service d'authentification pour signer les tokens.

##### Lancement de l'appplication localement
docker compose up --build -d

Le routage est assuré par Traefik via la règle `PathPrefix(`/`)` et un entrypoint web sur le port 80.

## Accès et endpoints utiles
- API (via Traefik) :
    - Base_URL : http://localhost
	- Health: http://localhost/actuator/health
	- Métriques: http://localhost/actuator/prometheus
	- Documentation OpenAPI (si exposée par services derrière le Gateway)
- Dashboard Traefik: http://localhost:8080

### Endpoint Auth_service

- `POST /api/v1/auth/register`
```json
{
  "firstName": "Michel",
  "lastName": "Tremblay",
  "email": "micheltremblay@gmail.com",
  "password": "Test1234!",
  "phoneNumber": "5142345678",
  "dateOfBirth": "1990-08-16",
  "address": "1234 rue de LaPlace",
  "city": "Montreal",
  "postalCode": "H6G1F3"
}
```

- `POST /api/v1/auth/login`
```json
{
    "email" : "micheltremblay@gmail.com",
    "password" : "Test1234!"
}
```

- `POST /api/v1/auth/verify-otp`
```json
{
  "email": "micheltremblay@gmail.com",
  "code": ""
}
```

- `POST /api/v1/auth/refresh`
```json
Cookie Refresh Token
```

- `POST /api/v1/auth/logout`
```json
Header Authorization Bearer <JWTtoken>
```

### Endpoint Market_service

- `GET /api/v1/market/data`
```json
Header Authorization Bearer <JWTtoken>
```

- `GET /api/v1/market/symbols`
```json
Header Authorization Bearer <JWTtoken>
```

- `GET /api/v1/market/data/{symbol}`
```json
Header Authorization Bearer <JWTtoken>
```

### Endpoint Order_service

- `POST /api/v1/order`
```json
Header Authorization Bearer <JWTtoken>

{
  "idempotencyKey": "b7d5b962-6e3d-4e7d-b4f1-6c5d17bff6f3",
  "stockSymbol": "AAPL",
  "side": "BUY",
  "type": "LIMIT",
  "quantity": 10,
  "limitPrice": null
}
```

- `POST /api/v1/order/user`
```json
Header Authorization Bearer <JWTtoken>
```


### Endpoint Wallet_service

- `GET /api/v1/wallet/me`
```json
Header Authorization Bearer <JWTtoken>
```

- `GET /api/v1/wallet/transactions`
```json
Header Authorization Bearer <JWTtoken>
```

- `POST /api/v1/wallet/credit`
```json
Header Authorization Bearer <JWTtoken>

{
    "amount": 100
}
```

- `POST /api/v1/wallet/debit`
```json
Header Authorization Bearer <JWTtoken>

{
    "amount": 100
}
```

## Sécurité et propagation d'identité
Le Gateway applique un filtre global qui :
- Extrait le JWT depuis `Authorization: Bearer <token>` ou le cookie `accessToken`
- Valide le JWT et extrait `userId`, `email`, `role`
- Ajoute des en-têtes aux requêtes vers les microservices :
	- `X-User-Id` — identifiant utilisateur
	- `X-User-Email` — email utilisateur
	- `X-User-Role` — rôle (ex: USER/ADMIN)
	- `X-Client-Real-IP` — IP réelle du client
	- `X-Client-User-Agent` — User-Agent client
	- `X-Gateway-Secret` — signature HMAC pour sécuriser la confiance entre Gateway et microservices

Les microservices valident `X-Gateway-Secret` avec le même `GATEWAY_SECRET` et reconstruisent l'auth côté backend.

Endpoints publics (sans JWT) au niveau du filtre :
- `/api/v1/auth/*`, `/actuator/health`, `/actuator/prometheus`, `/v3/api-docs`, `/swagger-ui*`, `/ws/market`

Les endpoints d'ordre/portefeuille/marché peuvent être déclarés "publics" au niveau WebFlux Security, mais restent soumis au filtre d'auth du Gateway s'ils ne sont pas listés comme publics. Concrètement :
- pour `POST /api/v1/order` et `GET /api/v1/order/user`, fournissez un JWT valide.

## Observabilité
- Health check : `GET /actuator/health`
- Prometheus : `GET /actuator/prometheus`
	- Le job Prometheus peut scraper `gateway-service:8080/actuator/prometheus` sur le réseau docker
	- Depuis l'hôte, Traefik expose `http://localhost/actuator/prometheus`

## Dépannage
- `401 Unauthorized` :
	- JWT manquant/invalidé/expiré, ou en-tête `Authorization` absent
- `403 Forbidden` :
	- Signature `X-Gateway-Secret` invalide (vérifier `GATEWAY_SECRET` côté Gateway et microservices)
- Pas de routage / 404:
	- Vérifier que `gateway-service` est `healthy` et que les labels Traefik sont bien appliqués
	- Vérifier que tous les services partagent le réseau `brokerx-network`
