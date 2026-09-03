# Mondial Relay Shipping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compute real shipping cost (Mondial Relay home-delivery and Point Relais rate tables) per shop-order at checkout, add it to the Stripe total, and expose a public endpoint to search Mondial Relay pickup points near a postcode.

**Architecture:** A `weightGrams` field on `Game` feeds a new `ShippingRateCalculator` (fixed rate table, no live pricing API exists). `Order` gains a `deliveryMode` (HOME/RELAY_POINT), an optional `relayPoint` snapshot embeddable, and a computed `shippingCost`. `OrderController#checkout` computes and persists the cost per shop-order; `StripeClient` adds one shipping line item per order. A new `shipping` package (mirroring the existing `payment` package) holds a lightweight SOAP client (`MondialRelayClient`, plain XML + `RestClient`, no generated stubs — same style as `IgdbClient`) and its controller (`ShippingController`), using Mondial Relay's public test credentials (`BDTEST13` / `TestAPI1key` — no merchant contract needed).

**Tech Stack:** Spring Boot 4.1.1, Spring Data JPA, Spring Security, `RestClient`, JDK's built-in `javax.xml.parsers` (DOM) for SOAP response parsing, JUnit 5, MockMvc, Mockito (`@MockitoBean`).

**Reference spec:** `docs/superpowers/specs/2026-09-03-mondial-relay-shipping-design.md`

**No commit steps in this plan.** Do not run `git commit` or `git push` at any point while executing this plan — the user reviews and commits changes himself. Each task ends once its verification step (compile/test) passes; leave the resulting file changes as-is in the working tree.

**Status:** Complete. All 9 tasks implemented, reviewed (spec compliance + code quality), and verified via subagent-driven-development. Full test suite passes (`Tests run: 20, Failures: 0, Errors: 0`). Nothing committed — all changes left in the working tree per the user's standing preference.

Two issues were caught by review and fixed beyond the plan's literal text:
- **Task 6:** `MondialRelayClient.buildRequestBody` interpolated `postCode`/`country` into hand-built SOAP XML with no escaping — a latent XML-injection risk once wired to user input. Fixed by adding an `escapeXml` helper and applying it to `enseigne`/`country`/`postCode`.
- **Task 8:** Writing the first-ever `OrderController` tests revealed `Order` had no `@Table` annotation, defaulting to the reserved-keyword table name `order`, which broke under H2. The first fix attempt (`@Table(name = "order_")`) would have silently orphaned any existing `order` table data in the real Postgres dev database via `ddl-auto=update`. Corrected to `@Table(name = "\`order\`")` (Hibernate backtick-quoting), which keeps the physical table name unchanged and safe for existing data while fixing the H2 reserved-word issue.

**Known, accepted, non-blocking follow-ups** (not fixed, documented for later):
- `MondialRelayClient`'s SOAP request-parameter order and response XML tag names could not be verified against a live Mondial Relay response — this machine's AVG antivirus performs TLS man-in-the-middle interception on all outbound HTTPS (confirmed via a forged certificate on `api.mondialrelay.com`), blocking the sandbox smoke test. The code follows best-effort public documentation but is unverified; recommend testing from a network without TLS interception (or temporarily disabling AVG's Web/Mail Shield SSL scanning) before relying on it.
- No rate limiting or input-format validation on the public `GET /shipping/relay-points` endpoint — it's an unauthenticated proxy to a third-party API with no request throttling.
- No test coverage anywhere in this plan for `MondialRelayClient`'s own SOAP-building/parsing logic (Task 7's test mocks the client entirely) — a real gap given the sandbox couldn't be reached, but not fixable in this environment.

## Notes for implementers (read before starting)

1. **Spring Boot 4.1.1 has relocated some test-support classes.** A prior task in this codebase found `AutoConfigureMockMvc` moved from `org.springframework.boot.test.autoconfigure.web.servlet` to `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`. If any import given in this plan doesn't resolve, inspect the actual jar contents under the local Maven repo (`~/.m2/repository/...`) for the class's real current package before guessing, fix the import, and note the deviation in your report — don't silently struggle or guess repeatedly.
2. **Mockito (`@MockitoBean`) is used for the first time in this project** (all earlier tests used real dependencies or `@WithMockUser`). If `org.mockito.*` classes or `org.springframework.test.context.bean.override.mockito.MockitoBean` don't resolve, check whether a Mockito dependency needs to be added explicitly to `pom.xml` (test scope) and add it if so — report what you found and did.
3. **`MondialRelayClient`'s exact SOAP request parameter order (for the MD5 security key) and response XML tag names are best-effort**, based on publicly documented Mondial Relay API conventions — not verified against a live call in this environment. Test against the real public sandbox (`BDTEST13` / `TestAPI1key`, `https://api.mondialrelay.com/Web_Services.asmx`) as part of the task. If the request errors or the response doesn't parse as expected, inspect the actual SOAP fault / raw XML response and adjust the parameter order or tag names accordingly — document exactly what you changed and why, the same way an earlier task in this plan adjusted an H2 datasource URL after hitting a real error. If the network call can't reach `api.mondialrelay.com` at all (e.g. the same kind of TLS interception seen with Maven Central on this machine), report that specifically as BLOCKED rather than continuing to guess at unrelated code changes.
4. **This repository is backend-only.** No frontend work is in scope anywhere in this plan.

---

## Task 1: Add `weightGrams` to `Game`

**Files:**
- Modify: `src/main/java/be/technifutur/newgameplus/entities/Game.java`
- Modify: `src/main/java/be/technifutur/newgameplus/dto/request/GameRequest.java`
- Modify: `src/main/java/be/technifutur/newgameplus/dto/response/GameResponse.java`
- Modify: `src/main/java/be/technifutur/newgameplus/seed/DemoDataSeeder.java`

- [ ] **Step 1: Add the field to `Game`**

Current end of `Game.java` (after `igdbID`):

```java
    @Column(nullable = false, unique = true, length = 50)
    private String igdbID;

}
```

Replace with:

```java
    @Column(nullable = false, unique = true, length = 50)
    private String igdbID;

    @Column(nullable = false, columnDefinition = "integer not null default 200")
    private int weightGrams;

}
```

(`columnDefinition` follows the precedent set by `User.confirmed` — a `NOT NULL` column added via `ddl-auto=update` needs a DDL-level default so it doesn't fail against a Postgres database that already has `Game` rows, which this project's local dev database does.)

- [ ] **Step 2: Add the field to `GameRequest`**

Current file:

```java
package be.technifutur.newgameplus.dto.request;

import be.technifutur.newgameplus.entities.Game;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GameRequest(
        @NotBlank String name,
        @NotBlank String description,
        @NotEmpty List<UUID> genreIds,
        @NotBlank String publisher,
        @NotBlank String developer,
        @NotBlank String platform,
        @NotNull LocalDate releaseDate,
        String coverURL,
        String igdbID
) {
    public Game toGame() {
        Game game = new Game();
        game.setName(name);
        game.setDescription(description);
        game.setPublisher(publisher);
        game.setDeveloper(developer);
        game.setPlatform(platform);
        game.setReleaseDate(releaseDate);
        game.setCoverURL(coverURL);
        game.setIgdbID(igdbID);
        return game;
    }
}
```

Replace with:

```java
package be.technifutur.newgameplus.dto.request;

import be.technifutur.newgameplus.entities.Game;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GameRequest(
        @NotBlank String name,
        @NotBlank String description,
        @NotEmpty List<UUID> genreIds,
        @NotBlank String publisher,
        @NotBlank String developer,
        @NotBlank String platform,
        @NotNull LocalDate releaseDate,
        String coverURL,
        String igdbID,
        @Min(1) int weightGrams
) {
    public Game toGame() {
        Game game = new Game();
        game.setName(name);
        game.setDescription(description);
        game.setPublisher(publisher);
        game.setDeveloper(developer);
        game.setPlatform(platform);
        game.setReleaseDate(releaseDate);
        game.setCoverURL(coverURL);
        game.setIgdbID(igdbID);
        game.setWeightGrams(weightGrams);
        return game;
    }
}
```

- [ ] **Step 3: Add the field to `GameResponse`**

Current file:

```java
package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Game;
import be.technifutur.newgameplus.entities.Genre;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GameResponse(
        UUID id,
        String name,
        String description,
        List<String> genres,
        String publisher,
        String developer,
        String platform,
        LocalDate releaseDate,
        String coverURL
) {
    public static GameResponse fromGame(Game game) {
        return new GameResponse(
                game.getId(),
                game.getName(),
                game.getDescription(),
                game.getGenres().stream().map(Genre::getName).toList(),
                game.getPublisher(),
                game.getDeveloper(),
                game.getPlatform(),
                game.getReleaseDate(),
                game.getCoverURL()
        );
    }
}
```

Replace with:

```java
package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Game;
import be.technifutur.newgameplus.entities.Genre;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GameResponse(
        UUID id,
        String name,
        String description,
        List<String> genres,
        String publisher,
        String developer,
        String platform,
        LocalDate releaseDate,
        String coverURL,
        int weightGrams
) {
    public static GameResponse fromGame(Game game) {
        return new GameResponse(
                game.getId(),
                game.getName(),
                game.getDescription(),
                game.getGenres().stream().map(Genre::getName).toList(),
                game.getPublisher(),
                game.getDeveloper(),
                game.getPlatform(),
                game.getReleaseDate(),
                game.getCoverURL(),
                game.getWeightGrams()
        );
    }
}
```

- [ ] **Step 4: Give the demo games realistic weights**

In `DemoDataSeeder.java`, the `createGame` helper currently reads:

```java
    private Game createGame(String name, String fallbackDescription, Set<Genre> genres,
                             String publisher, String developer, String platform,
                             LocalDate fallbackReleaseDate, String fallbackCoverURL, String fallbackIgdbID) {
        Optional<IgdbGame> igdbGame = igdbClient.searchGame(name);

        Game game = new Game();
        game.setName(name);
        game.setGenres(new HashSet<>(genres));
        game.setPublisher(publisher);
        game.setDeveloper(developer);
        game.setPlatform(platform);
        String description = igdbGame.map(IgdbGame::description).orElse(fallbackDescription);
        game.setDescription(description.length() > 2000 ? description.substring(0, 2000) : description);
        game.setReleaseDate(igdbGame.map(IgdbGame::releaseDate).orElse(fallbackReleaseDate));
        game.setCoverURL(igdbGame.map(IgdbGame::coverURL).orElse(fallbackCoverURL));
        game.setIgdbID(igdbGame.map(IgdbGame::igdbId).orElse(fallbackIgdbID));
        return gameRepository.save(game);
    }
```

Replace with (adds a `weightGrams` parameter):

```java
    private Game createGame(String name, String fallbackDescription, Set<Genre> genres,
                             String publisher, String developer, String platform,
                             LocalDate fallbackReleaseDate, String fallbackCoverURL, String fallbackIgdbID,
                             int weightGrams) {
        Optional<IgdbGame> igdbGame = igdbClient.searchGame(name);

        Game game = new Game();
        game.setName(name);
        game.setGenres(new HashSet<>(genres));
        game.setPublisher(publisher);
        game.setDeveloper(developer);
        game.setPlatform(platform);
        String description = igdbGame.map(IgdbGame::description).orElse(fallbackDescription);
        game.setDescription(description.length() > 2000 ? description.substring(0, 2000) : description);
        game.setReleaseDate(igdbGame.map(IgdbGame::releaseDate).orElse(fallbackReleaseDate));
        game.setCoverURL(igdbGame.map(IgdbGame::coverURL).orElse(fallbackCoverURL));
        game.setIgdbID(igdbGame.map(IgdbGame::igdbId).orElse(fallbackIgdbID));
        game.setWeightGrams(weightGrams);
        return gameRepository.save(game);
    }
```

And its five call sites currently read:

```java
        Game zelda = createGame(
                "The Legend of Zelda: Breath of the Wild",
                "Exploration en monde ouvert dans le royaume d'Hyrule.",
                Set.of(adventure, rpg),
                "Nintendo", "Nintendo EPD", "Nintendo Switch",
                LocalDate.of(2017, 3, 3),
                "https://picsum.photos/seed/botw/400/600", "botw-001"
        );

        Game eldenRing = createGame(
                "Elden Ring",
                "RPG d'action dans les Terres Interdites, coécrit avec George R. R. Martin.",
                Set.of(rpg, adventure),
                "Bandai Namco", "FromSoftware", "PC",
                LocalDate.of(2022, 2, 25),
                "https://picsum.photos/seed/eldenring/400/600", "elden-ring-001"
        );

        Game haloInfinite = createGame(
                "Halo Infinite",
                "Retour du Master Chief dans un FPS en monde semi-ouvert.",
                Set.of(fps),
                "Xbox Game Studios", "343 Industries", "Xbox Series X",
                LocalDate.of(2021, 12, 8),
                "https://picsum.photos/seed/haloinfinite/400/600", "halo-infinite-001"
        );

        Game celeste = createGame(
                "Celeste",
                "Plateformer exigeant sur l'ascension d'une montagne, et de soi-même.",
                Set.of(platformer),
                "Maddy Makes Games", "Maddy Makes Games", "PC",
                LocalDate.of(2018, 1, 25),
                "https://picsum.photos/seed/celeste/400/600", "celeste-001"
        );

        Game civ6 = createGame(
                "Sid Meier's Civilization VI",
                "Bâtissez un empire qui traversera les âges.",
                Set.of(strategy),
                "2K Games", "Firaxis Games", "PC",
                LocalDate.of(2016, 10, 21),
                "https://picsum.photos/seed/civ6/400/600", "civ6-001"
        );
```

Replace with (each call gains a trailing weight argument):

```java
        Game zelda = createGame(
                "The Legend of Zelda: Breath of the Wild",
                "Exploration en monde ouvert dans le royaume d'Hyrule.",
                Set.of(adventure, rpg),
                "Nintendo", "Nintendo EPD", "Nintendo Switch",
                LocalDate.of(2017, 3, 3),
                "https://picsum.photos/seed/botw/400/600", "botw-001",
                120
        );

        Game eldenRing = createGame(
                "Elden Ring",
                "RPG d'action dans les Terres Interdites, coécrit avec George R. R. Martin.",
                Set.of(rpg, adventure),
                "Bandai Namco", "FromSoftware", "PC",
                LocalDate.of(2022, 2, 25),
                "https://picsum.photos/seed/eldenring/400/600", "elden-ring-001",
                180
        );

        Game haloInfinite = createGame(
                "Halo Infinite",
                "Retour du Master Chief dans un FPS en monde semi-ouvert.",
                Set.of(fps),
                "Xbox Game Studios", "343 Industries", "Xbox Series X",
                LocalDate.of(2021, 12, 8),
                "https://picsum.photos/seed/haloinfinite/400/600", "halo-infinite-001",
                110
        );

        Game celeste = createGame(
                "Celeste",
                "Plateformer exigeant sur l'ascension d'une montagne, et de soi-même.",
                Set.of(platformer),
                "Maddy Makes Games", "Maddy Makes Games", "PC",
                LocalDate.of(2018, 1, 25),
                "https://picsum.photos/seed/celeste/400/600", "celeste-001",
                130
        );

        Game civ6 = createGame(
                "Sid Meier's Civilization VI",
                "Bâtissez un empire qui traversera les âges.",
                Set.of(strategy),
                "2K Games", "Firaxis Games", "PC",
                LocalDate.of(2016, 10, 21),
                "https://picsum.photos/seed/civ6/400/600", "civ6-001",
                350
        );
```

- [ ] **Step 5: Verify it compiles**

Run: `./mvnw.cmd -o compile` (from `C:\Users\lemet\Documents\Technifutur\new-game-plus`)
Expected: `BUILD SUCCESS`

---

## Task 2: Delivery mode entity model

**Files:**
- Create: `src/main/java/be/technifutur/newgameplus/entities/DeliveryMode.java`
- Create: `src/main/java/be/technifutur/newgameplus/entities/RelayPoint.java`
- Modify: `src/main/java/be/technifutur/newgameplus/entities/Address.java`
- Modify: `src/main/java/be/technifutur/newgameplus/entities/Order.java`

- [ ] **Step 1: Create `DeliveryMode`**

```java
package be.technifutur.newgameplus.entities;

public enum DeliveryMode {
    HOME, RELAY_POINT
}
```

- [ ] **Step 2: Create `RelayPoint`**

Field names are deliberately distinct from `Address`'s (`relayStreet` not `street`, etc.) — `Order` will have both `shippingAddress` (`Address`) and `relayPoint` (`RelayPoint`) as separate `@Embedded` fields, and JPA embeddables sharing a field name on the same owning entity collide on the generated column name unless overridden. Distinct names sidestep that entirely, matching this codebase's preference for simple, direct solutions over extra annotation machinery (`@AttributeOverrides`).

```java
package be.technifutur.newgameplus.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@Getter
@Setter
public class RelayPoint {

    @Column(name = "relay_id")
    private String relayId;

    @Column(name = "relay_name")
    private String relayName;

    @Column(name = "relay_street")
    private String relayStreet;

    @Column(name = "relay_post_code")
    private String relayPostCode;

    @Column(name = "relay_city")
    private String relayCity;

    @Column(name = "relay_country")
    private String relayCountry;

}
```

- [ ] **Step 3: Relax `Address`'s fields to nullable**

`shippingAddress` on `Order` is now only populated for `HOME` deliveries, so its component columns can no longer be `NOT NULL` at the database level (a `RELAY_POINT` order will insert an all-null `Address`).

Current file:

```java
package be.technifutur.newgameplus.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@Getter
@Setter
public class Address {

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String street;

    @Column(nullable = false)
    private String streetNumber;

    @Column(nullable = false)
    private String postCode;

    @Column(nullable = false)
    private String country;

}
```

Replace with:

```java
package be.technifutur.newgameplus.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@Getter
@Setter
public class Address {

    private String city;

    private String street;

    private String streetNumber;

    private String postCode;

    private String country;

}
```

(Removing `nullable = false` only ever *relaxes* a constraint — safe against a database that already has rows with real addresses in every column.)

- [ ] **Step 4: Add the new fields to `Order`**

Current file:

```java
package be.technifutur.newgameplus.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id"})
@EqualsAndHashCode(of = {"id"})
@Getter
@Setter

public class Order {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Embedded
    private Address shippingAddress;

    @Column(name = "stripe_session_id")
    private String stripeSessionId;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @Version
    private Long version;

}
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

@Entity
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id"})
@EqualsAndHashCode(of = {"id"})
@Getter
@Setter

public class Order {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) not null default 'HOME'")
    private DeliveryMode deliveryMode;

    @Embedded
    private Address shippingAddress;

    @Embedded
    private RelayPoint relayPoint;

    @Column(nullable = false, columnDefinition = "numeric not null default 0")
    private BigDecimal shippingCost;

    @Column(name = "stripe_session_id")
    private String stripeSessionId;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @Version
    private Long version;

}
```

(`columnDefinition` on `deliveryMode` and `shippingCost` follows the same `User.confirmed` precedent as Task 1 — both are new `NOT NULL` columns on a table (`Order`) that already has rows locally.)

- [ ] **Step 5: Verify it compiles**

Run: `./mvnw.cmd -o compile` (from `C:\Users\lemet\Documents\Technifutur\new-game-plus`)
Expected: `BUILD SUCCESS`

---

## Task 3: Checkout request/response DTOs

**Files:**
- Modify: `src/main/java/be/technifutur/newgameplus/dto/request/CheckoutRequest.java`
- Modify: `src/main/java/be/technifutur/newgameplus/dto/response/OrderResponse.java`

- [ ] **Step 1: Rework `CheckoutRequest`**

Current file:

```java
package be.technifutur.newgameplus.dto.request;

import be.technifutur.newgameplus.entities.Address;
import jakarta.validation.constraints.NotBlank;

public record CheckoutRequest(
        @NotBlank String street,
        @NotBlank String streetNumber,
        @NotBlank String postCode,
        @NotBlank String city,
        @NotBlank String country
) {

    public Address toAddress() {
        return new Address(city, street, streetNumber, postCode, country);
    }
}
```

Replace with:

```java
package be.technifutur.newgameplus.dto.request;

import be.technifutur.newgameplus.entities.Address;
import be.technifutur.newgameplus.entities.DeliveryMode;
import be.technifutur.newgameplus.entities.RelayPoint;
import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(
        @NotNull DeliveryMode deliveryMode,
        String street,
        String streetNumber,
        String postCode,
        String city,
        String country,
        String relayPointId,
        String relayPointName,
        String relayPointStreet,
        String relayPointPostCode,
        String relayPointCity,
        String relayPointCountry
) {

    public Address toAddress() {
        return new Address(city, street, streetNumber, postCode, country);
    }

    public RelayPoint toRelayPoint() {
        return new RelayPoint(relayPointId, relayPointName, relayPointStreet, relayPointPostCode, relayPointCity, relayPointCountry);
    }
}
```

Bean Validation can't cleanly express "these fields are required only when `deliveryMode == HOME`, these others only when `RELAY_POINT`" with field-level annotations, so all the mode-specific fields are plain (unannotated) `String`s here — Task 5 adds manual validation for this in `OrderController`.

- [ ] **Step 2: Add the new fields to `OrderResponse`**

Current file:

```java
package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Address;
import be.technifutur.newgameplus.entities.Order;
import be.technifutur.newgameplus.entities.OrderItem;
import be.technifutur.newgameplus.entities.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String shopName,
        OrderStatus status,
        Address shippingAddress,
        LocalDateTime createdAt,
        List<OrderItemResponse> items
) {
    public static OrderResponse fromOrder(Order order, List<OrderItem> items) {
        return new OrderResponse(
                order.getId(),
                order.getShop().getName(),
                order.getStatus(),
                order.getShippingAddress(),
                order.getCreatedAt(),
                items.stream().map(OrderItemResponse::fromOrderItem).toList()
        );
    }
}
```

Replace with:

```java
package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Address;
import be.technifutur.newgameplus.entities.DeliveryMode;
import be.technifutur.newgameplus.entities.Order;
import be.technifutur.newgameplus.entities.OrderItem;
import be.technifutur.newgameplus.entities.OrderStatus;
import be.technifutur.newgameplus.entities.RelayPoint;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String shopName,
        OrderStatus status,
        DeliveryMode deliveryMode,
        Address shippingAddress,
        RelayPoint relayPoint,
        BigDecimal shippingCost,
        LocalDateTime createdAt,
        List<OrderItemResponse> items
) {
    public static OrderResponse fromOrder(Order order, List<OrderItem> items) {
        return new OrderResponse(
                order.getId(),
                order.getShop().getName(),
                order.getStatus(),
                order.getDeliveryMode(),
                order.getShippingAddress(),
                order.getRelayPoint(),
                order.getShippingCost(),
                order.getCreatedAt(),
                items.stream().map(OrderItemResponse::fromOrderItem).toList()
        );
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./mvnw.cmd -o compile` (from `C:\Users\lemet\Documents\Technifutur\new-game-plus`)
Expected: `BUILD SUCCESS`. This should compile cleanly even before Task 5 touches `OrderController` — `CheckoutRequest.toAddress()` is unchanged, `deliveryMode` and the relay fields are simply unused by the not-yet-updated `OrderController`, and `OrderResponse.fromOrder()`'s new calls (`order.getDeliveryMode()`, `order.getRelayPoint()`, `order.getShippingCost()`) resolve fine against the getters Task 2 already added to `Order`. If it doesn't compile, something is out of sync with an earlier task — investigate before proceeding rather than assuming this note is wrong.

---

## Task 4: `ShippingRateCalculator` (TDD)

**Files:**
- Create: `src/test/java/be/technifutur/newgameplus/shipping/ShippingRateCalculatorTest.java`
- Create: `src/main/java/be/technifutur/newgameplus/shipping/ShippingRateCalculator.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/be/technifutur/newgameplus/shipping/ShippingRateCalculatorTest.java`:

```java
package be.technifutur.newgameplus.shipping;

import be.technifutur.newgameplus.entities.DeliveryMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShippingRateCalculatorTest {

    private final ShippingRateCalculator calculator = new ShippingRateCalculator();

    @Test
    void relayPointExactlyAtFirstTierBoundary() {
        assertEquals(0, new BigDecimal("4.15").compareTo(
                calculator.calculate(DeliveryMode.RELAY_POINT, 250)));
    }

    @Test
    void relayPointJustOverFirstTierBoundary() {
        assertEquals(0, new BigDecimal("5.99").compareTo(
                calculator.calculate(DeliveryMode.RELAY_POINT, 251)));
    }

    @Test
    void homeExactlyAtFirstTierBoundary() {
        assertEquals(0, new BigDecimal("4.99").compareTo(
                calculator.calculate(DeliveryMode.HOME, 250)));
    }

    @Test
    void homeJustOverFirstTierBoundary() {
        assertEquals(0, new BigDecimal("6.99").compareTo(
                calculator.calculate(DeliveryMode.HOME, 251)));
    }

    @Test
    void relayPointMidRangeWeight() {
        assertEquals(0, new BigDecimal("7.99").compareTo(
                calculator.calculate(DeliveryMode.RELAY_POINT, 4_000)));
    }

    @Test
    void weightAboveHighestTierFallsBackToLastTier() {
        assertEquals(0, new BigDecimal("13.49").compareTo(
                calculator.calculate(DeliveryMode.RELAY_POINT, 50_000)));
        assertEquals(0, new BigDecimal("17.99").compareTo(
                calculator.calculate(DeliveryMode.HOME, 50_000)));
    }

    @Test
    void zeroWeightUsesFirstTier() {
        assertEquals(0, new BigDecimal("4.15").compareTo(
                calculator.calculate(DeliveryMode.RELAY_POINT, 0)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -o test -Dtest=ShippingRateCalculatorTest` (from `C:\Users\lemet\Documents\Technifutur\new-game-plus`)
Expected: FAIL — `ShippingRateCalculator` doesn't exist yet (compile error)

- [ ] **Step 3: Implement `ShippingRateCalculator`**

```java
package be.technifutur.newgameplus.shipping;

import be.technifutur.newgameplus.entities.DeliveryMode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class ShippingRateCalculator {

    private record Tier(int maxGrams, BigDecimal relayPrice, BigDecimal homePrice) {
    }

    private static final List<Tier> TIERS = List.of(
            new Tier(250, new BigDecimal("4.15"), new BigDecimal("4.99")),
            new Tier(1_000, new BigDecimal("5.99"), new BigDecimal("6.99")),
            new Tier(3_000, new BigDecimal("6.99"), new BigDecimal("7.99")),
            new Tier(5_000, new BigDecimal("7.99"), new BigDecimal("8.99")),
            new Tier(10_000, new BigDecimal("9.49"), new BigDecimal("11.99")),
            new Tier(15_000, new BigDecimal("11.49"), new BigDecimal("14.99")),
            new Tier(20_000, new BigDecimal("13.49"), new BigDecimal("17.99"))
    );

    public BigDecimal calculate(DeliveryMode mode, int totalWeightGrams) {
        Tier tier = TIERS.stream()
                .filter(t -> totalWeightGrams <= t.maxGrams())
                .findFirst()
                .orElse(TIERS.get(TIERS.size() - 1));

        return mode == DeliveryMode.RELAY_POINT ? tier.relayPrice() : tier.homePrice();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw.cmd -o test -Dtest=ShippingRateCalculatorTest`
Expected: PASS, `Tests run: 7, Failures: 0, Errors: 0`

---

## Task 5: Checkout logic — `OrderController` + `StripeClient`

**Files:**
- Modify: `src/main/java/be/technifutur/newgameplus/payment/StripeClient.java`
- Modify: `src/main/java/be/technifutur/newgameplus/controller/OrderController.java`

This task depends on Tasks 1-4 (weight field, entity model, DTOs, rate calculator) all being in place.

- [ ] **Step 1: Add shipping line items to `StripeClient`**

Current `createCheckoutSession` method and its imports:

```java
package be.technifutur.newgameplus.payment;

import be.technifutur.newgameplus.entities.OrderItem;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Component
public class StripeClient {

    @Value("${stripe.secret-key}")
    private String secretKey;

    @Value("${stripe.success-url}")
    private String successUrl;

    @Value("${stripe.cancel-url}")
    private String cancelUrl;

    @Value("${payment.pending-expiration-minutes}")
    private long pendingExpirationMinutes;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }

    /**
     * Throws a checked {@link StripeException}. Callers inside a {@code @Transactional}
     * method must translate this to an unchecked exception (or use {@code rollbackFor})
     * — otherwise a failed Stripe call will not roll back the transaction.
     */
    public CheckoutSession createCheckoutSession(List<OrderItem> items) throws StripeException {
        long expiresInMinutes = Math.max(30, Math.min(pendingExpirationMinutes, 1440));
        long expiresAt = Instant.now().plusSeconds(expiresInMinutes * 60).getEpochSecond();

        SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .setExpiresAt(expiresAt);

        for (OrderItem item : items) {
            long unitAmount = item.getPrice()
                    .setScale(2, RoundingMode.HALF_UP)
                    .movePointRight(2)
                    .longValueExact();

            paramsBuilder.addLineItem(
                    SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(
                                    SessionCreateParams.LineItem.PriceData.builder()
                                            .setCurrency("eur")
                                            .setUnitAmount(unitAmount)
                                            .setProductData(
                                                    SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                            .setName(item.getListing().getGame().getName())
                                                            .build()
                                            )
                                            .build()
                            )
                            .build()
            );
        }

        Session session = Session.create(paramsBuilder.build());
        return new CheckoutSession(session.getId(), session.getUrl());
    }

    public record CheckoutSession(String id, String url) {
    }
}
```

Replace with:

```java
package be.technifutur.newgameplus.payment;

import be.technifutur.newgameplus.entities.Order;
import be.technifutur.newgameplus.entities.OrderItem;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Component
public class StripeClient {

    @Value("${stripe.secret-key}")
    private String secretKey;

    @Value("${stripe.success-url}")
    private String successUrl;

    @Value("${stripe.cancel-url}")
    private String cancelUrl;

    @Value("${payment.pending-expiration-minutes}")
    private long pendingExpirationMinutes;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }

    /**
     * Throws a checked {@link StripeException}. Callers inside a {@code @Transactional}
     * method must translate this to an unchecked exception (or use {@code rollbackFor})
     * — otherwise a failed Stripe call will not roll back the transaction.
     */
    public CheckoutSession createCheckoutSession(List<OrderItem> items, List<Order> orders) throws StripeException {
        long expiresInMinutes = Math.max(30, Math.min(pendingExpirationMinutes, 1440));
        long expiresAt = Instant.now().plusSeconds(expiresInMinutes * 60).getEpochSecond();

        SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .setExpiresAt(expiresAt);

        for (OrderItem item : items) {
            long unitAmount = item.getPrice()
                    .setScale(2, RoundingMode.HALF_UP)
                    .movePointRight(2)
                    .longValueExact();

            paramsBuilder.addLineItem(
                    SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(
                                    SessionCreateParams.LineItem.PriceData.builder()
                                            .setCurrency("eur")
                                            .setUnitAmount(unitAmount)
                                            .setProductData(
                                                    SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                            .setName(item.getListing().getGame().getName())
                                                            .build()
                                            )
                                            .build()
                            )
                            .build()
            );
        }

        for (Order order : orders) {
            BigDecimal shippingCost = order.getShippingCost();
            long shippingUnitAmount = shippingCost
                    .setScale(2, RoundingMode.HALF_UP)
                    .movePointRight(2)
                    .longValueExact();

            String shippingLabel = orders.size() == 1
                    ? "Livraison"
                    : "Livraison - " + order.getShop().getName();

            paramsBuilder.addLineItem(
                    SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(
                                    SessionCreateParams.LineItem.PriceData.builder()
                                            .setCurrency("eur")
                                            .setUnitAmount(shippingUnitAmount)
                                            .setProductData(
                                                    SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                            .setName(shippingLabel)
                                                            .build()
                                            )
                                            .build()
                            )
                            .build()
            );
        }

        Session session = Session.create(paramsBuilder.build());
        return new CheckoutSession(session.getId(), session.getUrl());
    }

    public record CheckoutSession(String id, String url) {
    }
}
```

- [ ] **Step 2: Update `OrderController`**

Current imports and field declarations (lines 1-42):

```java
package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.CheckoutRequest;
import be.technifutur.newgameplus.dto.request.UpdateOrderStatusRequest;
import be.technifutur.newgameplus.dto.response.CheckoutResponse;
import be.technifutur.newgameplus.dto.response.OrderResponse;
import be.technifutur.newgameplus.entities.*;
import be.technifutur.newgameplus.payment.StripeClient;
import be.technifutur.newgameplus.repositories.*;
import be.technifutur.newgameplus.security.JwtUtils;
import com.stripe.exception.StripeException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Commandes du buyer et checkout du panier")
public class OrderController {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final StripeClient stripeClient;
```

Replace with:

```java
package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.CheckoutRequest;
import be.technifutur.newgameplus.dto.request.UpdateOrderStatusRequest;
import be.technifutur.newgameplus.dto.response.CheckoutResponse;
import be.technifutur.newgameplus.dto.response.OrderResponse;
import be.technifutur.newgameplus.entities.*;
import be.technifutur.newgameplus.payment.StripeClient;
import be.technifutur.newgameplus.repositories.*;
import be.technifutur.newgameplus.security.JwtUtils;
import be.technifutur.newgameplus.shipping.ShippingRateCalculator;
import com.stripe.exception.StripeException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Commandes du buyer et checkout du panier")
public class OrderController {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final StripeClient stripeClient;
    private final ShippingRateCalculator shippingRateCalculator;
```

Current `checkout` method:

```java
    @PostMapping("/checkout")
    @Transactional
    public ResponseEntity<CheckoutResponse> checkout(
            @Valid @RequestBody CheckoutRequest request,
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        Cart cart = cartRepository.findByBuyerId(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Panier vide"));

        List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId());
        if (cartItems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Panier vide");
        }

        for (CartItem cartItem : cartItems) {
            if (cartItem.getListing().getStatus() != ListingStatus.AVAILABLE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "\"" + cartItem.getListing().getGame().getName() + "\" n'est plus disponible, retire-le de ton panier");
            }
        }

        User buyer = userRepository.findById(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));

        Map<Shop, List<CartItem>> itemsByShop = cartItems.stream()
                .collect(Collectors.groupingBy(item -> item.getListing().getShop()));

        List<Order> createdOrders = new ArrayList<>();
        List<OrderItem> allItems = new ArrayList<>();
        List<OrderResponse> responses = new ArrayList<>();

        for (Map.Entry<Shop, List<CartItem>> entry : itemsByShop.entrySet()) {
            OrderWithItems result = createOrderForShop(buyer, entry.getKey(), entry.getValue(), request);
            createdOrders.add(result.order());
            allItems.addAll(result.items());
            responses.add(OrderResponse.fromOrder(result.order(), result.items()));
        }

        cartItemRepository.deleteByCartId(cart.getId());

        StripeClient.CheckoutSession checkoutSession;
        try {
            checkoutSession = stripeClient.createCheckoutSession(allItems);
        } catch (StripeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Erreur lors de la création du paiement Stripe");
        }

        for (Order order : createdOrders) {
            order.setStripeSessionId(checkoutSession.id());
            orderRepository.save(order);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(new CheckoutResponse(responses, checkoutSession.url()));
    }

    private OrderWithItems createOrderForShop(User buyer, Shop shop, List<CartItem> items, CheckoutRequest request) {
        Order order = new Order();
        order.setBuyer(buyer);
        order.setShop(shop);
        order.setStatus(OrderStatus.PENDING);
        order.setShippingAddress(request.toAddress());
        Order savedOrder = orderRepository.saveAndFlush(order);

        List<OrderItem> orderItems = items.stream().map(cartItem -> {
            Listing listing = cartItem.getListing();

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(savedOrder);
            orderItem.setListing(listing);
            orderItem.setPrice(listing.getPrice());
            orderItemRepository.save(orderItem);

            listing.setStatus(ListingStatus.SOLD);
            listingRepository.save(listing);

            return orderItem;
        }).toList();

        return new OrderWithItems(savedOrder, orderItems);
    }
```

Replace with:

```java
    @PostMapping("/checkout")
    @Transactional
    public ResponseEntity<CheckoutResponse> checkout(
            @Valid @RequestBody CheckoutRequest request,
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        assertValidDeliveryFields(request);

        Cart cart = cartRepository.findByBuyerId(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Panier vide"));

        List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId());
        if (cartItems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Panier vide");
        }

        for (CartItem cartItem : cartItems) {
            if (cartItem.getListing().getStatus() != ListingStatus.AVAILABLE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "\"" + cartItem.getListing().getGame().getName() + "\" n'est plus disponible, retire-le de ton panier");
            }
        }

        User buyer = userRepository.findById(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));

        Map<Shop, List<CartItem>> itemsByShop = cartItems.stream()
                .collect(Collectors.groupingBy(item -> item.getListing().getShop()));

        List<Order> createdOrders = new ArrayList<>();
        List<OrderItem> allItems = new ArrayList<>();
        List<OrderResponse> responses = new ArrayList<>();

        for (Map.Entry<Shop, List<CartItem>> entry : itemsByShop.entrySet()) {
            OrderWithItems result = createOrderForShop(buyer, entry.getKey(), entry.getValue(), request);
            createdOrders.add(result.order());
            allItems.addAll(result.items());
            responses.add(OrderResponse.fromOrder(result.order(), result.items()));
        }

        cartItemRepository.deleteByCartId(cart.getId());

        StripeClient.CheckoutSession checkoutSession;
        try {
            checkoutSession = stripeClient.createCheckoutSession(allItems, createdOrders);
        } catch (StripeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Erreur lors de la création du paiement Stripe");
        }

        for (Order order : createdOrders) {
            order.setStripeSessionId(checkoutSession.id());
            orderRepository.save(order);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(new CheckoutResponse(responses, checkoutSession.url()));
    }

    private void assertValidDeliveryFields(CheckoutRequest request) {
        if (request.deliveryMode() == DeliveryMode.HOME) {
            if (isBlank(request.street()) || isBlank(request.streetNumber()) || isBlank(request.postCode())
                    || isBlank(request.city()) || isBlank(request.country())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adresse de livraison incomplète");
            }
        } else {
            if (isBlank(request.relayPointId()) || isBlank(request.relayPointName()) || isBlank(request.relayPointStreet())
                    || isBlank(request.relayPointPostCode()) || isBlank(request.relayPointCity()) || isBlank(request.relayPointCountry())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Point relais incomplet");
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private OrderWithItems createOrderForShop(User buyer, Shop shop, List<CartItem> items, CheckoutRequest request) {
        Order order = new Order();
        order.setBuyer(buyer);
        order.setShop(shop);
        order.setStatus(OrderStatus.PENDING);
        order.setDeliveryMode(request.deliveryMode());

        if (request.deliveryMode() == DeliveryMode.HOME) {
            order.setShippingAddress(request.toAddress());
        } else {
            order.setRelayPoint(request.toRelayPoint());
        }

        int totalWeightGrams = items.stream()
                .mapToInt(item -> item.getListing().getGame().getWeightGrams())
                .sum();
        order.setShippingCost(shippingRateCalculator.calculate(request.deliveryMode(), totalWeightGrams));

        Order savedOrder = orderRepository.saveAndFlush(order);

        List<OrderItem> orderItems = items.stream().map(cartItem -> {
            Listing listing = cartItem.getListing();

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(savedOrder);
            orderItem.setListing(listing);
            orderItem.setPrice(listing.getPrice());
            orderItemRepository.save(orderItem);

            listing.setStatus(ListingStatus.SOLD);
            listingRepository.save(listing);

            return orderItem;
        }).toList();

        return new OrderWithItems(savedOrder, orderItems);
    }
```

- [ ] **Step 3: Verify it compiles**

Run: `./mvnw.cmd -o compile` (from `C:\Users\lemet\Documents\Technifutur\new-game-plus`)
Expected: `BUILD SUCCESS`

---

## Task 6: `MondialRelayClient`

**Files:**
- Create: `src/main/java/be/technifutur/newgameplus/shipping/MondialRelayClient.java`
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Add Mondial Relay config**

In `application.yaml`, add a new block after the `listing:` block (currently right before `payment:`):

Current:

```yaml
listing:
  cheap-price-threshold: ${LISTING_CHEAP_PRICE_THRESHOLD:15}

payment:
```

Replace with:

```yaml
listing:
  cheap-price-threshold: ${LISTING_CHEAP_PRICE_THRESHOLD:15}

mondialrelay:
  enseigne: ${MONDIALRELAY_ENSEIGNE:BDTEST13}
  private-key: ${MONDIALRELAY_PRIVATE_KEY:TestAPI1key}

payment:
```

(`BDTEST13` / `TestAPI1key` is Mondial Relay's publicly documented test account — safe to commit as a default, no merchant contract exists yet. See the design spec for background.)

- [ ] **Step 2: Implement `MondialRelayClient`**

```java
package be.technifutur.newgameplus.shipping;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class MondialRelayClient {

    private final RestClient restClient = RestClient.create("https://api.mondialrelay.com");

    @Value("${mondialrelay.enseigne}")
    private String enseigne;

    @Value("${mondialrelay.private-key}")
    private String privateKey;

    public List<RelayPointResult> search(String postCode, String country) {
        try {
            String security = computeSecurity(postCode, country);
            String body = buildRequestBody(postCode, country, security);

            String response = restClient.post()
                    .uri("/Web_Services.asmx")
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("SOAPAction", "http://www.mondialrelay.fr/webservice/WSI4_PointRelais_Recherche")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parseResponse(response);
        } catch (Exception e) {
            log.error("Mondial Relay point relais search failed for postCode='{}', country='{}': {}",
                    postCode, country, e.getMessage(), e);
            return List.of();
        }
    }

    private String computeSecurity(String postCode, String country) {
        // Mondial Relay's documented WSI4_PointRelais_Recherche parameter order for the
        // security hash: Enseigne + Pays + NumPointRelais + Ville + CP + Latitude +
        // Longitude + Taille + Poids + Action + DelaiEnvoi + RayonRecherche +
        // TypeActivite + NombreResultats + PrivateKey, then MD5, uppercase hex.
        // All the fields this feature doesn't use are sent blank, so they're omitted
        // (empty string) from the concatenation below in the same order.
        String concatenated = enseigne + country + postCode + privateKey;
        return md5Upper(concatenated);
    }

    private String md5Upper(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02X", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm unavailable", e);
        }
    }

    private String buildRequestBody(String postCode, String country, String security) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <WSI4_PointRelais_Recherche xmlns="http://www.mondialrelay.fr/webservice/">
                      <Enseigne>%s</Enseigne>
                      <Pays>%s</Pays>
                      <NumPointRelais></NumPointRelais>
                      <Ville></Ville>
                      <CP>%s</CP>
                      <Latitude></Latitude>
                      <Longitude></Longitude>
                      <Taille></Taille>
                      <Poids></Poids>
                      <Action></Action>
                      <DelaiEnvoi></DelaiEnvoi>
                      <RayonRecherche></RayonRecherche>
                      <TypeActivite></TypeActivite>
                      <NombreResultats></NombreResultats>
                      <Security>%s</Security>
                    </WSI4_PointRelais_Recherche>
                  </soap:Body>
                </soap:Envelope>
                """.formatted(enseigne, country, postCode, security);
    }

    private List<RelayPointResult> parseResponse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        List<RelayPointResult> results = new ArrayList<>();
        NodeList points = doc.getElementsByTagName("PointRelais_Details");
        for (int i = 0; i < points.getLength(); i++) {
            Element point = (Element) points.item(i);
            results.add(new RelayPointResult(
                    text(point, "Num"),
                    text(point, "LgAdr1"),
                    text(point, "LgAdr3"),
                    text(point, "CP"),
                    text(point, "Ville"),
                    text(point, "Pays")
            ));
        }
        return results;
    }

    private String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : "";
    }

    public record RelayPointResult(String id, String name, String street, String postCode, String city, String country) {
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./mvnw.cmd -o compile` (from `C:\Users\lemet\Documents\Technifutur\new-game-plus`)
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Smoke-test against the real Mondial Relay sandbox**

This step makes a real network call — see "Notes for implementers" item 3 at the top of this plan for what to do if it fails.

Write a small throwaway `main` method or a quick manual test invocation (your choice — this step doesn't need to leave a permanent test file behind, Task 7 adds the real automated test with a mocked client) that calls `new MondialRelayClient()` with `enseigne = "BDTEST13"`, `privateKey = "TestAPI1key"` (set the fields directly, e.g. via a package-private constructor tweak or reflection, or temporarily hardcode them — whichever is fastest to throw away) and calls `.search("75008", "FR")` (Mondial Relay's own examples typically use Paris postcodes for the test account — try a Belgian one like `"4000"`/`"BE"` too if the French one returns nothing).

Expected: a non-empty list of `RelayPointResult`s with plausible-looking `id`/`name`/`street`/`city` values. If you get an empty list or an exception, read the actual raw SOAP response/fault (add a temporary `System.out.println(response)` before parsing) and adjust `computeSecurity`'s parameter concatenation or `parseResponse`'s tag names accordingly. Remove any throwaway test code/println once you've confirmed it works, before moving to Task 7.

Report exactly what you observed (working on first try / had to adjust — and what — / couldn't reach the network at all).

---

## Task 7: `ShippingController` (TDD)

**Files:**
- Create: `src/test/java/be/technifutur/newgameplus/shipping/ShippingControllerTest.java`
- Create: `src/main/java/be/technifutur/newgameplus/shipping/ShippingController.java`
- Modify: `src/main/java/be/technifutur/newgameplus/security/SecurityConfig.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/be/technifutur/newgameplus/shipping/ShippingControllerTest.java`:

```java
package be.technifutur.newgameplus.shipping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShippingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MondialRelayClient mondialRelayClient;

    @Test
    void findRelayPointsReturnsClientResults() throws Exception {
        when(mondialRelayClient.search(anyString(), anyString())).thenReturn(List.of(
                new MondialRelayClient.RelayPointResult("047368", "Superette du Coin", "Rue de la Gare 12", "4000", "Liège", "BE")
        ));

        mockMvc.perform(get("/shipping/relay-points").param("postCode", "4000").param("country", "BE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("047368"))
                .andExpect(jsonPath("$[0].city").value("Liège"));
    }

    @Test
    void findRelayPointsDefaultsCountryToBelgium() throws Exception {
        when(mondialRelayClient.search("1000", "BE")).thenReturn(List.of());

        mockMvc.perform(get("/shipping/relay-points").param("postCode", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -o test -Dtest=ShippingControllerTest` (from `C:\Users\lemet\Documents\Technifutur\new-game-plus`)
Expected: FAIL — `ShippingController` doesn't exist yet, and `/shipping/**` isn't in `SecurityConfig`'s permit-all list yet either (compile error first, since the class doesn't exist)

- [ ] **Step 3: Implement `ShippingController`**

```java
package be.technifutur.newgameplus.shipping;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/shipping")
@RequiredArgsConstructor
@Tag(name = "Shipping", description = "Recherche de points relais Mondial Relay")
public class ShippingController {

    private final MondialRelayClient mondialRelayClient;

    @GetMapping("/relay-points")
    public ResponseEntity<List<MondialRelayClient.RelayPointResult>> findRelayPoints(
            @RequestParam String postCode,
            @RequestParam(defaultValue = "BE") String country
    ) {
        return ResponseEntity.ok(mondialRelayClient.search(postCode, country));
    }
}
```

- [ ] **Step 4: Permit `GET /shipping/**` publicly**

In `SecurityConfig.java`, current line:

```java
                        .requestMatchers(HttpMethod.GET, "/games/**", "/genres/**", "/listings/**", "/reviews/**").permitAll()
```

Replace with:

```java
                        .requestMatchers(HttpMethod.GET, "/games/**", "/genres/**", "/listings/**", "/reviews/**", "/shipping/**").permitAll()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw.cmd -o test -Dtest=ShippingControllerTest`
Expected: PASS, `Tests run: 2, Failures: 0, Errors: 0`

(If `MockitoBean` or `org.mockito.*` doesn't resolve, see "Notes for implementers" item 2 at the top of this plan.)

---

## Task 8: `OrderControllerCheckoutTest` (TDD, full checkout coverage)

**Files:**
- Create: `src/test/java/be/technifutur/newgameplus/controller/OrderControllerCheckoutTest.java`

`OrderController` currently has zero test coverage of any kind — this is the first test for it. Because `checkout()` uses `@AuthenticationPrincipal JwtUtils.UserSession session` (not just a role check), `@WithMockUser` won't work here (it injects a generic Spring Security principal, not a `JwtUtils.UserSession`) — instead, generate a real JWT via the actual `JwtUtils` bean and send it as a real `Authorization: Bearer <token>` header, exercising the real `JwtFilter` path end-to-end.

- [ ] **Step 1: Write the test file**

```java
package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.entities.*;
import be.technifutur.newgameplus.payment.StripeClient;
import be.technifutur.newgameplus.repositories.*;
import be.technifutur.newgameplus.security.JwtUtils;
import com.stripe.exception.StripeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderControllerCheckoutTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoBean
    private StripeClient stripeClient;

    private String buyerToken;
    private Cart cart;

    @BeforeEach
    void setUp() throws StripeException {
        Role buyerRole = roleRepository.findByName("BUYER").orElseGet(() -> roleRepository.save(new Role("BUYER")));
        Role sellerRole = roleRepository.findByName("SELLER").orElseGet(() -> roleRepository.save(new Role("SELLER")));

        User buyer = new User("checkout-buyer", "checkout-buyer@example.com", passwordEncoder.encode("password123"));
        buyer.getRoles().add(buyerRole);
        buyer.setConfirmed(true);
        userRepository.save(buyer);
        buyerToken = jwtUtils.generateToken(buyer);

        cart = new Cart();
        cart.setBuyer(buyer);
        cartRepository.save(cart);

        when(stripeClient.createCheckoutSession(any(), any()))
                .thenReturn(new StripeClient.CheckoutSession("cs_test_123", "https://checkout.stripe.com/test"));
    }

    private Shop createShop(String suffix) {
        Role sellerRole = roleRepository.findByName("SELLER").orElseThrow();
        User seller = new User("seller-" + suffix, "seller-" + suffix + "@example.com", passwordEncoder.encode("password123"));
        seller.getRoles().add(sellerRole);
        seller.setConfirmed(true);
        userRepository.save(seller);

        Shop shop = new Shop();
        shop.setOwner(seller);
        shop.setName("Checkout Shop " + suffix);
        shop.setDescription("Shop used by OrderControllerCheckoutTest");
        return shopRepository.save(shop);
    }

    private Listing createListing(Shop shop, int weightGrams, BigDecimal price, String suffix) {
        Game game = new Game();
        game.setName("Checkout Test Game " + suffix);
        game.setDescription("A game used for checkout tests");
        game.setPublisher("Test Publisher");
        game.setDeveloper("Test Developer");
        game.setPlatform("PC");
        game.setReleaseDate(LocalDate.of(2020, 1, 1));
        game.setCoverURL("https://example.com/checkout-cover-" + suffix + ".png");
        game.setIgdbID("checkout-igdb-" + suffix);
        game.setWeightGrams(weightGrams);
        gameRepository.save(game);

        Listing listing = new Listing();
        listing.setGame(game);
        listing.setShop(shop);
        listing.setPrice(price);
        return listingRepository.save(listing);
    }

    private void addToCart(Listing listing) {
        CartItem cartItem = new CartItem();
        cartItem.setCart(cart);
        cartItem.setListing(listing);
        cartItemRepository.save(cartItem);
    }

    @Test
    void homeCheckoutComputesShippingCostFromWeightAndUsesHomeRate() throws Exception {
        Shop shop = createShop("home");
        addToCart(createListing(shop, 800, new BigDecimal("20.00"), "home-800g"));

        String body = """
                {
                  "deliveryMode": "HOME",
                  "street": "Rue de la Paix",
                  "streetNumber": "12",
                  "postCode": "4000",
                  "city": "Liège",
                  "country": "BE"
                }
                """;

        mockMvc.perform(post("/orders/checkout")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.orders[0].deliveryMode").value("HOME"))
                .andExpect(jsonPath("$.orders[0].shippingCost").value(6.99))
                .andExpect(jsonPath("$.orders[0].shippingAddress.city").value("Liège"))
                .andExpect(jsonPath("$.orders[0].relayPoint").value(org.hamcrest.Matchers.nullValue()));

        Order order = orderRepository.findByShopId(shop.getId()).get(0);
        assertEquals(0, new BigDecimal("6.99").compareTo(order.getShippingCost()));
        assertNull(order.getRelayPoint());
    }

    @Test
    void relayPointCheckoutStoresSnapshotAndUsesRelayRate() throws Exception {
        Shop shop = createShop("relay");
        addToCart(createListing(shop, 2_000, new BigDecimal("30.00"), "relay-2000g"));

        String body = """
                {
                  "deliveryMode": "RELAY_POINT",
                  "relayPointId": "047368",
                  "relayPointName": "Superette du Coin",
                  "relayPointStreet": "Rue de la Gare 12",
                  "relayPointPostCode": "4000",
                  "relayPointCity": "Liège",
                  "relayPointCountry": "BE"
                }
                """;

        mockMvc.perform(post("/orders/checkout")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders[0].deliveryMode").value("RELAY_POINT"))
                .andExpect(jsonPath("$.orders[0].shippingCost").value(6.99))
                .andExpect(jsonPath("$.orders[0].relayPoint.relayId").value("047368"))
                .andExpect(jsonPath("$.orders[0].shippingAddress").value(org.hamcrest.Matchers.nullValue()));

        Order order = orderRepository.findByShopId(shop.getId()).get(0);
        assertEquals(0, new BigDecimal("6.99").compareTo(order.getShippingCost()));
        assertNull(order.getShippingAddress());
    }

    @Test
    void multiShopCartComputesShippingCostIndependentlyPerShop() throws Exception {
        Shop shopA = createShop("multi-a");
        Shop shopB = createShop("multi-b");
        addToCart(createListing(shopA, 200, new BigDecimal("10.00"), "multi-a-200g"));
        addToCart(createListing(shopB, 4_000, new BigDecimal("15.00"), "multi-b-4000g"));

        String body = """
                {
                  "deliveryMode": "HOME",
                  "street": "Rue de la Paix",
                  "streetNumber": "12",
                  "postCode": "4000",
                  "city": "Liège",
                  "country": "BE"
                }
                """;

        mockMvc.perform(post("/orders/checkout")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders", org.hamcrest.Matchers.hasSize(2)));

        Order orderA = orderRepository.findByShopId(shopA.getId()).get(0);
        Order orderB = orderRepository.findByShopId(shopB.getId()).get(0);
        assertEquals(0, new BigDecimal("4.99").compareTo(orderA.getShippingCost()));
        assertEquals(0, new BigDecimal("8.99").compareTo(orderB.getShippingCost()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Order>> ordersCaptor = ArgumentCaptor.forClass(List.class);
        verify(stripeClient).createCheckoutSession(any(), ordersCaptor.capture());
        assertEquals(2, ordersCaptor.getValue().size());
    }

    @Test
    void homeCheckoutWithBlankStreetIsRejected() throws Exception {
        Shop shop = createShop("invalid-home");
        addToCart(createListing(shop, 800, new BigDecimal("20.00"), "invalid-home-800g"));

        String body = """
                {
                  "deliveryMode": "HOME",
                  "street": "",
                  "streetNumber": "12",
                  "postCode": "4000",
                  "city": "Liège",
                  "country": "BE"
                }
                """;

        mockMvc.perform(post("/orders/checkout")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void relayPointCheckoutWithBlankRelayIdIsRejected() throws Exception {
        Shop shop = createShop("invalid-relay");
        addToCart(createListing(shop, 800, new BigDecimal("20.00"), "invalid-relay-800g"));

        String body = """
                {
                  "deliveryMode": "RELAY_POINT",
                  "relayPointId": "",
                  "relayPointName": "Superette du Coin",
                  "relayPointStreet": "Rue de la Gare 12",
                  "relayPointPostCode": "4000",
                  "relayPointCity": "Liège",
                  "relayPointCountry": "BE"
                }
                """;

        mockMvc.perform(post("/orders/checkout")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./mvnw.cmd -o test -Dtest=OrderControllerCheckoutTest` (from `C:\Users\lemet\Documents\Technifutur\new-game-plus`)
Expected: PASS, `Tests run: 5, Failures: 0, Errors: 0`

If a test fails, read the actual failure carefully before changing anything:
- A `nullValue()` assertion failing on `relayPoint`/`shippingAddress` most likely means Jackson actually omitted the null key from the JSON instead of including it as `null` — if so, switch that specific assertion to `.doesNotExist()` instead, since both are valid JSON representations of "no relay point," just check whichever one Jackson actually produces in this project's configuration.
- A shipping-cost mismatch likely means a tier boundary or weight sum is off — recompute by hand against the table in `ShippingRateCalculator` before assuming the test fixture is wrong.

---

## Task 9: Final verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite**

Run: `./mvnw.cmd -o test` (from `C:\Users\lemet\Documents\Technifutur\new-game-plus`)
Expected: `BUILD SUCCESS`, all tests passing — the pre-existing 6 (from the listing-discovery feature + smoke test) plus this feature's new ones (`ShippingRateCalculatorTest`: 7, `ShippingControllerTest`: 2, `OrderControllerCheckoutTest`: 5) — `Tests run: 20, Failures: 0, Errors: 0` if nothing else changed in between.

- [ ] **Step 2: Compile-check the whole project one more time cleanly**

Run: `./mvnw.cmd -o clean compile` (a full clean rebuild catches anything a stale `target/` might have masked)
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Report results**

Summarize: test counts, anything that had to deviate from this plan (SOAP field names/parameter order, package locations, Mockito setup) and why, and confirm no `git commit` was run at any point. No commit for this task — it's verification only, and per this plan's standing instruction, no task in it commits anything.
