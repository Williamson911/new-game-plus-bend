# Homepage listing discovery — design

## Contexte

La page d'accueil du front (maquette fournie) affiche trois sections
au-dessus des listings paginés classiques :

- **Derniers arrivages** — annonces les plus récentes
- **Pépites** — annonces mises en avant par curation éditoriale
- **Petits prix** — annonces bon marché

Aucune de ces trois vues n'existe côté backend aujourd'hui. `Listing`
n'a ni date de création, ni flag de mise en avant, et
`ListingController` n'expose qu'un `findAll` paginé filtré sur
`AVAILABLE` sans tri dédié.

## Modèle de données

Sur `Listing` (`entities/Listing.java`), deux nouveaux champs :

- `createdAt: LocalDateTime`, annoté `@CreationTimestamp`, non
  modifiable — même pattern que `Order.createdAt`.
- `featured: boolean`, défaut `false`.

`spring.jpa.hibernate.ddl-auto=update` ajoute les colonnes
automatiquement au démarrage ; pas de script de migration nécessaire
(le projet n'utilise ni Flyway ni Liquibase).

## Configuration

Nouvelle propriété dans `application.yaml` :

```yaml
listing:
  cheap-price-threshold: ${LISTING_CHEAP_PRICE_THRESHOLD:15}
```

Seuil par défaut 15€, ajustable sans recompiler — même pattern que
`payment.pending-expiration-minutes`.

## API

Sur `ListingController`, trois nouveaux endpoints publics en lecture,
tous filtrés sur `status = AVAILABLE` (comme `findAll` existant) et
acceptant `?limit=` (défaut 8, plafonné à 50) :

| Endpoint | Filtre | Tri |
|---|---|---|
| `GET /listings/latest?limit=` | — | `createdAt` décroissant |
| `GET /listings/cheap?limit=` | `price <= listing.cheap-price-threshold` | `price` croissant |
| `GET /listings/featured?limit=` | `featured = true` | `createdAt` décroissant |

Chacun renvoie directement `List<ListingResponse>` — pas de `Page`,
pas de métadonnées de pagination à déballer côté front.

Un endpoint d'écriture réservé ADMIN (curation centralisée, aucune
auto-désignation par les vendeurs) :

- `PATCH /listings/{id}/featured` — corps `{ "featured": boolean }`,
  `@PreAuthorize("hasRole('ADMIN')")`, même pattern que
  `GenreController`.

`ListingResponse` gagne un champ `featured` (utile pour un badge côté
front). `createdAt` reste interne, pas exposé.

### Note de routage

Les mappings littéraux `/listings/latest`, `/listings/cheap`,
`/listings/featured` cohabitent avec `/listings/{id}` (`UUID`) sans
conflit : Spring MVC priorise les patterns les plus spécifiques.

## Repository

`ListingRepository` gagne les méthodes nécessaires pour les trois
requêtes filtrées (statut + prix / statut + featured), triées via le
`Pageable` construit dans le contrôleur — pas de nouvelle méthode pour
`latest` qui réutilise `findByStatus` existant avec un `Sort` explicite.

## Données de démo

Dans `DemoDataSeeder` :
- une ou deux annonces existantes marquées `featured = true` (ex.
  Elden Ring) pour que `/listings/featured` ne soit pas vide en dev.
- au moins une annonce déjà sous le seuil de 15€ (Celeste à 14.90€ —
  déjà le cas, rien à changer).

## Tests

Le projet n'a aujourd'hui aucun test au-delà du `contextLoads()` par
défaut, et aucune base de données de test (seul `postgresql` est
présent, en scope `runtime`). Ajout :

- dépendance `com.h2database:h2` (scope `test`)
- profil `test` avec `src/test/resources/application-test.yaml`
  pointant sur une base H2 en mémoire (`MODE=PostgreSQL`)

Test d'intégration `@SpringBootTest @AutoConfigureMockMvc` sur les
nouveaux endpoints :

- `GET /listings/latest` → tri par date décroissante respecté, `limit`
  appliqué
- `GET /listings/cheap` → n'inclut que les annonces ≤ seuil configuré,
  triées par prix croissant
- `GET /listings/featured` → n'inclut que les annonces `featured = true`
- `PATCH /listings/{id}/featured` → `403` avec `@WithMockUser(roles =
  "BUYER")`, `200` + flag mis à jour avec `@WithMockUser(roles =
  "ADMIN")`
