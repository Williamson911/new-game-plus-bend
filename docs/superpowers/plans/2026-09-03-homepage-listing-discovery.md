# Homepage Listing Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three homepage discovery endpoints (`/listings/latest`, `/listings/cheap`, `/listings/featured`) and an admin-only endpoint to toggle a listing's "featured" flag, backed by new `createdAt`/`featured` fields on `Listing`.

**Architecture:** Two new columns on the existing `Listing` JPA entity (auto-migrated via `ddl-auto=update`), two new derived-query repository methods, four new `ListingController` endpoints reusing the existing `ListingResponse` DTO (extended with a `featured` field), and a new `FeaturedRequest` DTO for the admin toggle. A new H2-backed `test` Spring profile is introduced so these endpoints can be covered by `@SpringBootTest` + `MockMvc` integration tests — the project currently has zero test infrastructure beyond the default `contextLoads()` smoke test.

**Tech Stack:** Spring Boot 4.1.1, Spring Data JPA, Spring Security (method security), H2 (test scope), JUnit 5, MockMvc, Lombok.

**Reference spec:** `docs/superpowers/specs/2026-09-03-homepage-listing-discovery-design.md`

**Status:** Complete. Tasks 1-10 implemented, reviewed (spec compliance + code quality), and committed via subagent-driven-development. Full test suite passes (`Tests run: 6, Failures: 0, Errors: 0`).

A final whole-feature review (after Task 10) caught one real issue not visible in any single task's diff: `Listing.featured` lacked a DDL default (`columnDefinition`), which would have broken `ddl-auto=update` against any Postgres database that already had listings (Postgres rejects a `NOT NULL` column added with no default to a populated table). Fixed in commit `039741e`, following the same pattern already used by `User.confirmed`. Individual step checkboxes below were not all retroactively checked off — treat this status block as authoritative over the per-step checkboxes.

Known, accepted, non-blocking follow-ups (not fixed, documented for later):
- `Listing.createdAt` is not backfilled for pre-existing rows — on a database with listings from before this feature, those rows get `createdAt = NULL`, and Postgres sorts `NULL` first in `ORDER BY ... DESC`, so old undated listings would appear ahead of genuinely recent ones in `/listings/latest` until backfilled or naturally superseded by new listings.
- A pre-existing, unrelated bug: `PaymentScheduler`'s `@Scheduled` task throws `SQLGrammarException` against H2 in test runs because `order` is a reserved word there (works fine on real Postgres). Logged as background noise, doesn't fail any test. Not introduced by this feature.
- Minor DRY opportunity: the `limit` → `Pageable` clamping logic is duplicated across `findLatest`/`findCheap`/`findFeatured`.
- No test coverage for `?limit=` clamping, the `DEFAULT_LIMIT` no-param case, or a PATCH-on-nonexistent-id 404.

---

## Task 1: Add `createdAt` and `featured` fields to `Listing` ✅

**Files:**
- Modify: `src/main/java/be/technifutur/newgameplus/entities/Listing.java`

- [x] **Step 1: Add the fields**

Current end of the file (lines 33-39):

```java
    @Column(nullable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ListingStatus status = ListingStatus.AVAILABLE;
}
```

Replace with:

```java
    @Column(nullable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ListingStatus status = ListingStatus.AVAILABLE;

    @Column(nullable = false)
    private boolean featured = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
```

And update the imports at the top of the file (lines 1-8):

```java
package be.technifutur.newgameplus.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;
```

Replace with:

```java
package be.technifutur.newgameplus.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
```

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw.cmd -o compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/be/technifutur/newgameplus/entities/Listing.java
git commit -m "$(cat <<'EOF'
feat: add createdAt and featured fields to Listing

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TdpWchPA2P9zbzcozDRLdi
EOF
)"
```

---

## Task 2: Add `listing.cheap-price-threshold` configuration

**Files:**
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Add the new config block**

Current lines 8-16:

```yaml
stripe:
  secret-key: ${STRIPE_SECRET_KEY:}
  webhook-secret: ${STRIPE_WEBHOOK_SECRET:}
  success-url: ${STRIPE_SUCCESS_URL:${app.frontend-url}/checkout/success}
  cancel-url: ${STRIPE_CANCEL_URL:${app.frontend-url}/checkout/cancel}

payment:
  pending-expiration-minutes: ${PAYMENT_PENDING_EXPIRATION_MINUTES:30}
  expiration-check-rate-ms: ${PAYMENT_EXPIRATION_CHECK_RATE_MS:900000}
```

Replace with:

```yaml
stripe:
  secret-key: ${STRIPE_SECRET_KEY:}
  webhook-secret: ${STRIPE_WEBHOOK_SECRET:}
  success-url: ${STRIPE_SUCCESS_URL:${app.frontend-url}/checkout/success}
  cancel-url: ${STRIPE_CANCEL_URL:${app.frontend-url}/checkout/cancel}

listing:
  cheap-price-threshold: ${LISTING_CHEAP_PRICE_THRESHOLD:15}

payment:
  pending-expiration-minutes: ${PAYMENT_PENDING_EXPIRATION_MINUTES:30}
  expiration-check-rate-ms: ${PAYMENT_EXPIRATION_CHECK_RATE_MS:900000}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.yaml
git commit -m "$(cat <<'EOF'
feat: add configurable cheap-price-threshold for listing discovery

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TdpWchPA2P9zbzcozDRLdi
EOF
)"
```

---

## Task 3: Add repository query methods

**Files:**
- Modify: `src/main/java/be/technifutur/newgameplus/repositories/ListingRepository.java`

- [ ] **Step 1: Add the two derived queries**

Current file:

```java
package be.technifutur.newgameplus.repositories;

import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.ListingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ListingRepository extends JpaRepository<Listing, UUID>,
        JpaSpecificationExecutor<Listing> {

    Page<Listing> findByStatus(ListingStatus status, Pageable pageable);

    List<Listing> findByShopId(UUID shopId);

    List<Listing> findByGameId(UUID gameId);
}
```

Replace with:

```java
package be.technifutur.newgameplus.repositories;

import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.ListingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface ListingRepository extends JpaRepository<Listing, UUID>,
        JpaSpecificationExecutor<Listing> {

    Page<Listing> findByStatus(ListingStatus status, Pageable pageable);

    Page<Listing> findByStatusAndPriceLessThanEqual(ListingStatus status, BigDecimal maxPrice, Pageable pageable);

    Page<Listing> findByStatusAndFeaturedTrue(ListingStatus status, Pageable pageable);

    List<Listing> findByShopId(UUID shopId);

    List<Listing> findByGameId(UUID gameId);
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw.cmd -o compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/be/technifutur/newgameplus/repositories/ListingRepository.java
git commit -m "$(cat <<'EOF'
feat: add price-threshold and featured repository queries to ListingRepository

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TdpWchPA2P9zbzcozDRLdi
EOF
)"
```

---

## Task 4: Add `FeaturedRequest` DTO and extend `ListingResponse`

**Files:**
- Create: `src/main/java/be/technifutur/newgameplus/dto/request/FeaturedRequest.java`
- Modify: `src/main/java/be/technifutur/newgameplus/dto/response/ListingResponse.java`

- [ ] **Step 1: Create `FeaturedRequest`**

```java
package be.technifutur.newgameplus.dto.request;

import jakarta.validation.constraints.NotNull;

public record FeaturedRequest(
        @NotNull Boolean featured
) {
}
```

- [ ] **Step 2: Extend `ListingResponse` with the `featured` field**

Current file:

```java
package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.ListingStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ListingResponse(
        UUID id,
        String gameName,
        String shopName,
        BigDecimal price,
        ListingStatus status,
        List<String> imageUrls
) {
    public static ListingResponse fromListing(Listing listing, List<String> imageUrls) {
        return new ListingResponse(
                listing.getId(),
                listing.getGame().getName(),
                listing.getShop().getName(),
                listing.getPrice(),
                listing.getStatus(),
                imageUrls
        );
    }
}
```

Replace with:

```java
package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.ListingStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ListingResponse(
        UUID id,
        String gameName,
        String shopName,
        BigDecimal price,
        ListingStatus status,
        boolean featured,
        List<String> imageUrls
) {
    public static ListingResponse fromListing(Listing listing, List<String> imageUrls) {
        return new ListingResponse(
                listing.getId(),
                listing.getGame().getName(),
                listing.getShop().getName(),
                listing.getPrice(),
                listing.getStatus(),
                listing.isFeatured(),
                imageUrls
        );
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./mvnw.cmd -o compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/be/technifutur/newgameplus/dto/request/FeaturedRequest.java src/main/java/be/technifutur/newgameplus/dto/response/ListingResponse.java
git commit -m "$(cat <<'EOF'
feat: add FeaturedRequest DTO and expose featured flag on ListingResponse

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TdpWchPA2P9zbzcozDRLdi
EOF
)"
```

---

## Task 5: Test infrastructure (H2 + `test` profile)

**Files:**
- Modify: `pom.xml`
- Create: `src/test/resources/application-test.yaml`
- Modify: `src/main/java/be/technifutur/newgameplus/seed/DemoDataSeeder.java`
- Modify: `src/test/java/be/technifutur/newgameplus/NewGamePlusApplicationTests.java`

**Why disable `DemoDataSeeder` under the `test` profile:** it's a `CommandLineRunner` that runs on every application context startup (including test contexts) and inserts fixed demo data (users, a shop, 5 listings). Leaving it enabled would make every test's assertions depend on that seed data staying exactly as-is, and would let it collide with fixtures tests create themselves (unique constraints on shop name, game name, etc.). Disabling it for `test` keeps each test in full control of its own data.

- [ ] **Step 1: Add H2 as a test-scoped dependency**

In `pom.xml`, current lines 100-109:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
```

Replace with:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Create the `test` profile datasource config**

Create `src/test/resources/application-test.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:new_game_plus_test;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1
    username: sa
    password:
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
    properties:
      hibernate:
        default_schema: public
    show-sql: false

listing:
  cheap-price-threshold: 15
```

- [ ] **Step 3: Disable `DemoDataSeeder` under the `test` profile**

In `DemoDataSeeder.java`, current lines 1-22:

```java
package be.technifutur.newgameplus.seed;

import be.technifutur.newgameplus.entities.*;
import be.technifutur.newgameplus.igdb.IgdbClient;
import be.technifutur.newgameplus.igdb.IgdbGame;
import be.technifutur.newgameplus.repositories.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Order(2)
public class DemoDataSeeder implements CommandLineRunner {
```

Replace with:

```java
package be.technifutur.newgameplus.seed;

import be.technifutur.newgameplus.entities.*;
import be.technifutur.newgameplus.igdb.IgdbClient;
import be.technifutur.newgameplus.igdb.IgdbGame;
import be.technifutur.newgameplus.repositories.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Order(2)
@Profile("!test")
public class DemoDataSeeder implements CommandLineRunner {
```

- [ ] **Step 4: Activate the `test` profile for the existing smoke test**

Current `NewGamePlusApplicationTests.java`:

```java
package be.technifutur.newgameplus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class NewGamePlusApplicationTests {

    @Test
    void contextLoads() {
    }

}
```

Replace with:

```java
package be.technifutur.newgameplus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NewGamePlusApplicationTests {

    @Test
    void contextLoads() {
    }

}
```

- [ ] **Step 5: Run it — first run needs network to download H2**

Run: `./mvnw.cmd test -Dtest=NewGamePlusApplicationTests`
Expected: `BUILD SUCCESS`, `Tests run: 1, Failures: 0, Errors: 0`

If this fails with `(certificate_unknown) PKIX path building failed` (the same TLS interception issue seen earlier when trying `spring-boot:run` on this machine/network), it means Maven Central can't be reached at all from the CLI here. In that case, open the project in IntelliJ and let it resolve/download the new `com.h2database:h2` dependency (Maven tool window → reload), which may go through a different network path, then re-run the test from IntelliJ's test runner instead of the CLI.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/test/resources/application-test.yaml src/main/java/be/technifutur/newgameplus/seed/DemoDataSeeder.java src/test/java/be/technifutur/newgameplus/NewGamePlusApplicationTests.java
git commit -m "$(cat <<'EOF'
test: add H2-backed test profile and disable demo seeding under it

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TdpWchPA2P9zbzcozDRLdi
EOF
)"
```

---

## Task 6: `GET /listings/latest`

**Files:**
- Create: `src/test/java/be/technifutur/newgameplus/controller/ListingDiscoveryControllerTest.java`
- Modify: `src/main/java/be/technifutur/newgameplus/controller/ListingController.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/be/technifutur/newgameplus/controller/ListingDiscoveryControllerTest.java`:

```java
package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.entities.Game;
import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.Shop;
import be.technifutur.newgameplus.entities.User;
import be.technifutur.newgameplus.repositories.GameRepository;
import be.technifutur.newgameplus.repositories.ListingRepository;
import be.technifutur.newgameplus.repositories.ShopRepository;
import be.technifutur.newgameplus.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ListingDiscoveryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ListingRepository listingRepository;

    private Shop shop;

    @BeforeEach
    void setUp() {
        User owner = new User("shop-owner", "shop-owner@example.com", "irrelevant-hash");
        userRepository.save(owner);

        shop = new Shop();
        shop.setOwner(owner);
        shop.setName("Discovery Test Shop");
        shop.setDescription("Shop used by ListingDiscoveryControllerTest");
        shopRepository.save(shop);
    }

    private Game createGame(String uniqueSuffix) {
        Game game = new Game();
        game.setName("Test Game " + uniqueSuffix);
        game.setDescription("A game used for tests");
        game.setPublisher("Test Publisher");
        game.setDeveloper("Test Developer");
        game.setPlatform("PC");
        game.setReleaseDate(LocalDate.of(2020, 1, 1));
        game.setCoverURL("https://example.com/cover-" + uniqueSuffix + ".png");
        game.setIgdbID("igdb-" + uniqueSuffix);
        return gameRepository.save(game);
    }

    private Listing createListing(Game game, BigDecimal price, boolean featured) {
        Listing listing = new Listing();
        listing.setGame(game);
        listing.setShop(shop);
        listing.setPrice(price);
        listing.setFeatured(featured);
        return listingRepository.save(listing);
    }

    @Test
    void latestReturnsMostRecentListingsFirst() throws Exception {
        Listing older = createListing(createGame("latest-older"), new BigDecimal("20.00"), false);
        Thread.sleep(5);
        Listing newer = createListing(createGame("latest-newer"), new BigDecimal("20.00"), false);

        mockMvc.perform(get("/listings/latest").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(newer.getId().toString()))
                .andExpect(jsonPath("$[1].id").value(older.getId().toString()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -o test -Dtest=ListingDiscoveryControllerTest#latestReturnsMostRecentListingsFirst`
Expected: FAIL — `404 Not Found` (`GET /listings/latest` doesn't exist yet)

- [ ] **Step 3: Add the endpoint**

In `ListingController.java`, add the imports (current lines 19-27):

```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
```

Replace with:

```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
```

Add `DEFAULT_LIMIT`/`MAX_LIMIT` constants and the `/latest` endpoint right after the field declarations, before the existing `findAll` method (current lines 38-48):

```java
    private final ListingRepository listingRepository;
    private final ListingImageRepository listingImageRepository;
    private final CartItemRepository cartItemRepository;
    private final GameRepository gameRepository;
    private final ShopRepository shopRepository;

    @GetMapping
    public ResponseEntity<Page<ListingResponse>> findAll(Pageable pageable) {
        Page<Listing> listings = listingRepository.findByStatus(ListingStatus.AVAILABLE, pageable);
        return ResponseEntity.ok(listings.map(this::toResponse));
    }
```

Replace with:

```java
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 50;

    private final ListingRepository listingRepository;
    private final ListingImageRepository listingImageRepository;
    private final CartItemRepository cartItemRepository;
    private final GameRepository gameRepository;
    private final ShopRepository shopRepository;

    @GetMapping
    public ResponseEntity<Page<ListingResponse>> findAll(Pageable pageable) {
        Page<Listing> listings = listingRepository.findByStatus(ListingStatus.AVAILABLE, pageable);
        return ResponseEntity.ok(listings.map(this::toResponse));
    }

    @GetMapping("/latest")
    public ResponseEntity<List<ListingResponse>> findLatest(
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit
    ) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        Pageable pageable = PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<ListingResponse> listings = listingRepository.findByStatus(ListingStatus.AVAILABLE, pageable)
                .map(this::toResponse)
                .getContent();
        return ResponseEntity.ok(listings);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw.cmd -o test -Dtest=ListingDiscoveryControllerTest#latestReturnsMostRecentListingsFirst`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/be/technifutur/newgameplus/controller/ListingDiscoveryControllerTest.java src/main/java/be/technifutur/newgameplus/controller/ListingController.java
git commit -m "$(cat <<'EOF'
feat: add GET /listings/latest endpoint

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TdpWchPA2P9zbzcozDRLdi
EOF
)"
```

---

## Task 7: `GET /listings/cheap`

**Files:**
- Modify: `src/test/java/be/technifutur/newgameplus/controller/ListingDiscoveryControllerTest.java`
- Modify: `src/main/java/be/technifutur/newgameplus/controller/ListingController.java`

- [ ] **Step 1: Write the failing test**

Add this method to `ListingDiscoveryControllerTest`, right after `latestReturnsMostRecentListingsFirst`:

```java

    @Test
    void cheapReturnsOnlyListingsAtOrBelowThreshold() throws Exception {
        Listing cheap = createListing(createGame("cheap-under"), new BigDecimal("10.00"), false);
        createListing(createGame("cheap-over"), new BigDecimal("25.00"), false);

        mockMvc.perform(get("/listings/cheap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(cheap.getId().toString()))
                .andExpect(jsonPath("$[0].price").value(10.00));
    }
```

(`listing.cheap-price-threshold` is `15` in `application-test.yaml`, so the 25.00€ listing must be excluded.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -o test -Dtest=ListingDiscoveryControllerTest#cheapReturnsOnlyListingsAtOrBelowThreshold`
Expected: FAIL — `404 Not Found` (`GET /listings/cheap` doesn't exist yet)

- [ ] **Step 3: Add the endpoint**

Add the `@Value` field and `/cheap` endpoint in `ListingController.java`, right after the `findLatest` method added in Task 6:

```java
    @GetMapping("/cheap")
    public ResponseEntity<List<ListingResponse>> findCheap(
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit
    ) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        Pageable pageable = PageRequest.of(0, size, Sort.by(Sort.Direction.ASC, "price"));
        List<ListingResponse> listings = listingRepository
                .findByStatusAndPriceLessThanEqual(ListingStatus.AVAILABLE, cheapPriceThreshold, pageable)
                .map(this::toResponse)
                .getContent();
        return ResponseEntity.ok(listings);
    }
```

And add the `cheapPriceThreshold` field, right after the `MAX_LIMIT` constant declared in Task 6:

```java
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 50;

    @Value("${listing.cheap-price-threshold}")
    private BigDecimal cheapPriceThreshold;

```

Add the two new imports at the top of the file, alongside the existing ones:

```java
import org.springframework.beans.factory.annotation.Value;
```

and

```java
import java.math.BigDecimal;
```

(place `import java.math.BigDecimal;` next to the existing `import java.util.List;` / `import java.util.UUID;` block at the bottom of the import list).

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw.cmd -o test -Dtest=ListingDiscoveryControllerTest#cheapReturnsOnlyListingsAtOrBelowThreshold`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/be/technifutur/newgameplus/controller/ListingDiscoveryControllerTest.java src/main/java/be/technifutur/newgameplus/controller/ListingController.java
git commit -m "$(cat <<'EOF'
feat: add GET /listings/cheap endpoint with configurable price threshold

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TdpWchPA2P9zbzcozDRLdi
EOF
)"
```

---

## Task 8: `GET /listings/featured`

**Files:**
- Modify: `src/test/java/be/technifutur/newgameplus/controller/ListingDiscoveryControllerTest.java`
- Modify: `src/main/java/be/technifutur/newgameplus/controller/ListingController.java`

- [ ] **Step 1: Write the failing test**

Add this method to `ListingDiscoveryControllerTest`, right after `cheapReturnsOnlyListingsAtOrBelowThreshold`. It also asserts that a `SOLD` featured listing is excluded, matching the spec's "filtered on `status = AVAILABLE`" rule:

```java

    @Test
    void featuredReturnsOnlyAvailableFeaturedListings() throws Exception {
        Listing featuredAvailable = createListing(createGame("featured-available"), new BigDecimal("30.00"), true);
        createListing(createGame("featured-not-flagged"), new BigDecimal("30.00"), false);

        Listing featuredSold = createListing(createGame("featured-sold"), new BigDecimal("30.00"), true);
        featuredSold.setStatus(be.technifutur.newgameplus.entities.ListingStatus.SOLD);
        listingRepository.save(featuredSold);

        mockMvc.perform(get("/listings/featured"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(featuredAvailable.getId().toString()));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -o test -Dtest=ListingDiscoveryControllerTest#featuredReturnsOnlyAvailableFeaturedListings`
Expected: FAIL — `404 Not Found` (`GET /listings/featured` doesn't exist yet)

- [ ] **Step 3: Add the endpoint**

Add the `/featured` endpoint in `ListingController.java`, right after the `findCheap` method added in Task 7:

```java
    @GetMapping("/featured")
    public ResponseEntity<List<ListingResponse>> findFeatured(
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit
    ) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        Pageable pageable = PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<ListingResponse> listings = listingRepository
                .findByStatusAndFeaturedTrue(ListingStatus.AVAILABLE, pageable)
                .map(this::toResponse)
                .getContent();
        return ResponseEntity.ok(listings);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw.cmd -o test -Dtest=ListingDiscoveryControllerTest#featuredReturnsOnlyAvailableFeaturedListings`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/be/technifutur/newgameplus/controller/ListingDiscoveryControllerTest.java src/main/java/be/technifutur/newgameplus/controller/ListingController.java
git commit -m "$(cat <<'EOF'
feat: add GET /listings/featured endpoint

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TdpWchPA2P9zbzcozDRLdi
EOF
)"
```

---

## Task 9: `PATCH /listings/{id}/featured` (ADMIN only)

**Files:**
- Modify: `src/test/java/be/technifutur/newgameplus/controller/ListingDiscoveryControllerTest.java`
- Modify: `src/main/java/be/technifutur/newgameplus/controller/ListingController.java`

- [ ] **Step 1: Write the failing tests**

Add these two methods to `ListingDiscoveryControllerTest`, right after `featuredReturnsOnlyAvailableFeaturedListings`:

```java

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "BUYER")
    void patchFeaturedIsForbiddenForNonAdmin() throws Exception {
        Listing listing = createListing(createGame("patch-forbidden"), new BigDecimal("30.00"), false);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/listings/{id}/featured", listing.getId())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"featured\": true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
    void patchFeaturedUpdatesFlagForAdmin() throws Exception {
        Listing listing = createListing(createGame("patch-allowed"), new BigDecimal("30.00"), false);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/listings/{id}/featured", listing.getId())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"featured\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featured").value(true));

        Listing reloaded = listingRepository.findById(listing.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(reloaded.isFeatured());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw.cmd -o test -Dtest=ListingDiscoveryControllerTest#patchFeaturedIsForbiddenForNonAdmin+patchFeaturedUpdatesFlagForAdmin`
Expected: FAIL — `404 Not Found` (`PATCH /listings/{id}/featured` doesn't exist yet)

- [ ] **Step 3: Add the endpoint**

Add the import for `FeaturedRequest` and `Valid` at the top of `ListingController.java`, alongside the existing DTO imports:

```java
import be.technifutur.newgameplus.dto.request.FeaturedRequest;
```

(`jakarta.validation.Valid` is already imported.)

Add the `/{id}/featured` endpoint right after the existing `updatePrice` method (current lines 77-93):

```java
    @PreAuthorize("hasRole('SELLER')")
    @PutMapping("/{id}")
    public ResponseEntity<ListingResponse> updatePrice(
            @PathVariable UUID id,
            @Valid @RequestBody ListingRequest request,
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        Listing listing = listingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing introuvable"));

        assertOwnedBySession(listing, session);

        listing.setPrice(request.price());
        listingRepository.save(listing);

        return ResponseEntity.ok(toResponse(listing));
    }
```

Insert immediately after it (before the `delete` method):

```java

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/featured")
    public ResponseEntity<ListingResponse> setFeatured(
            @PathVariable UUID id,
            @Valid @RequestBody FeaturedRequest request
    ) {
        Listing listing = listingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing introuvable"));

        listing.setFeatured(request.featured());
        listingRepository.save(listing);

        return ResponseEntity.ok(toResponse(listing));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw.cmd -o test -Dtest=ListingDiscoveryControllerTest#patchFeaturedIsForbiddenForNonAdmin+patchFeaturedUpdatesFlagForAdmin`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/be/technifutur/newgameplus/controller/ListingDiscoveryControllerTest.java src/main/java/be/technifutur/newgameplus/controller/ListingController.java
git commit -m "$(cat <<'EOF'
feat: add admin-only PATCH /listings/{id}/featured endpoint

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TdpWchPA2P9zbzcozDRLdi
EOF
)"
```

---

## Task 10: Mark a demo listing as featured

**Files:**
- Modify: `src/main/java/be/technifutur/newgameplus/seed/DemoDataSeeder.java`

This is dev/demo data only (the `test` profile no longer runs this seeder, per Task 5) — it makes `/listings/featured` non-empty when running the app locally via IntelliJ.

- [ ] **Step 1: Make `createListing` return the saved `Listing` and mark Elden Ring featured**

Current lines 116-121 and 142-148:

```java
        createListing(shop, zelda, new BigDecimal("39.90"));
        createListing(shop, eldenRing, new BigDecimal("49.90"));
        createListing(shop, haloInfinite, new BigDecimal("19.90"));
        createListing(shop, celeste, new BigDecimal("14.90"));
        createListing(shop, civ6, new BigDecimal("24.90"));
    }
```

Replace with:

```java
        createListing(shop, zelda, new BigDecimal("39.90"));
        Listing eldenRingListing = createListing(shop, eldenRing, new BigDecimal("49.90"));
        eldenRingListing.setFeatured(true);
        listingRepository.save(eldenRingListing);
        createListing(shop, haloInfinite, new BigDecimal("19.90"));
        createListing(shop, celeste, new BigDecimal("14.90"));
        createListing(shop, civ6, new BigDecimal("24.90"));
    }
```

And:

```java
    private void createListing(Shop shop, Game game, BigDecimal price) {
        Listing listing = new Listing();
        listing.setShop(shop);
        listing.setGame(game);
        listing.setPrice(price);
        listingRepository.save(listing);
    }
}
```

Replace with:

```java
    private Listing createListing(Shop shop, Game game, BigDecimal price) {
        Listing listing = new Listing();
        listing.setShop(shop);
        listing.setGame(game);
        listing.setPrice(price);
        return listingRepository.save(listing);
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw.cmd -o compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/be/technifutur/newgameplus/seed/DemoDataSeeder.java
git commit -m "$(cat <<'EOF'
feat: mark Elden Ring demo listing as featured

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TdpWchPA2P9zbzcozDRLdi
EOF
)"
```

---

## Task 11: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite**

Run: `./mvnw.cmd -o test`
Expected: `BUILD SUCCESS`, all tests pass (`NewGamePlusApplicationTests` + the 5 new tests in `ListingDiscoveryControllerTest`)

- [ ] **Step 2: Manual smoke test via IntelliJ**

Maven CLI `spring-boot:run` is known to fail on this machine/network (TLS certificate interception blocking `spring-boot-maven-plugin`'s own dependency resolution — confirmed unrelated to this feature). Start the app from IntelliJ instead (already confirmed working earlier this session), then check with a browser or `curl` against `http://localhost:8080`:

- `GET /listings/latest` → 200, JSON array, 5 demo listings max, most recent first
- `GET /listings/cheap` → 200, JSON array containing only "Celeste" (14.90€ ≤ 15€ threshold)
- `GET /listings/featured` → 200, JSON array containing only "Elden Ring"
- `PATCH /listings/{id}/featured` without a token → `401`
- `PATCH /listings/{id}/featured` with a BUYER/SELLER token → `403`
- `PATCH /listings/{id}/featured` with the seeded admin account (`admin` / `admin123`) and body `{"featured": true}` → `200`, and the listing now appears in `/listings/featured`

- [ ] **Step 3: Report results to the user**

No commit for this task — it's verification only.
