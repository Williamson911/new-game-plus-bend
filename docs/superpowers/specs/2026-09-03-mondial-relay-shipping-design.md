# Mondial Relay shipping cost calculation — design

## Contexte

The checkout flow (`OrderController#checkout`) currently creates one
`Order` per shop from the buyer's cart, attaches a single home
delivery `Address` (from `CheckoutRequest`) to every order, and sends
one Stripe Checkout Session covering the item prices only — no
shipping cost exists anywhere in the system.

The goal is to add a real (if simplified) shipping-cost calculation
backed by Mondial Relay, for both home delivery and Point Relais
(pickup point) delivery, computed per shop-order and added to the
Stripe total. No merchant contract with Mondial Relay exists yet, so
this uses their public test credentials (`Enseigne` code `BDTEST13`,
private key `TestAPI1key`) against the real Web Service SOAP API —
these are documented, publicly-known test credentials used by plugin
developers before a real contract is signed, not a security risk to
commit to `application.yaml`.

This repository is the backend only (Angular frontend project not yet
started — confirmed with the user). This feature only needs to expose
the right data and endpoints for a future frontend; no UI work here.

## Data model

**`Game`** (`entities/Game.java`) gains:
```java
@Column(nullable = false)
private int weightGrams;
```
Populated by the seller when creating a listing's underlying game
entry, via `GameRequest` (new `@Min(1) int weightGrams` field, mapped
in `toGame()`). `DemoDataSeeder`'s five demo games get realistic
values (e.g. 150–250g for a jewel-case-sized game).

**New enum** `entities/DeliveryMode.java`:
```java
public enum DeliveryMode { HOME, RELAY_POINT }
```

**New embeddable** `entities/RelayPoint.java` (same shape/pattern as
the existing `Address` embeddable):
```java
@Embeddable
public class RelayPoint {
    private String relayId;
    private String name;
    private String street;
    private String postCode;
    private String city;
    private String country;
}
```
This is a snapshot of the chosen point at checkout time — not a live
reference back to Mondial Relay — so displaying a past order's
delivery point never depends on a later API call succeeding.

**`Order`** (`entities/Order.java`) gains:
```java
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
private DeliveryMode deliveryMode;

@Embedded
private RelayPoint relayPoint; // null unless deliveryMode == RELAY_POINT

@Column(nullable = false)
private BigDecimal shippingCost;
```
`shippingAddress` (existing `Address` embeddable) becomes nullable —
populated only when `deliveryMode == HOME`. Having two `@Embedded`
fields on the same entity where exactly one is null at a time is
standard, safe Hibernate behavior: a wholly-null embedded value maps
to all-null component columns without error. Not a pattern already
used elsewhere in this codebase, but a well-established one.

**`CheckoutRequest`** (`dto/request/CheckoutRequest.java`) changes
from all-`@NotBlank` address fields to:
```java
public record CheckoutRequest(
        @NotNull DeliveryMode deliveryMode,
        // HOME fields — required only when deliveryMode == HOME
        String street,
        String streetNumber,
        String postCode,
        String city,
        String country,
        // RELAY_POINT fields — required only when deliveryMode == RELAY_POINT
        String relayPointId,
        String relayPointName,
        String relayPointStreet,
        String relayPointPostCode,
        String relayPointCity,
        String relayPointCountry
) {
}
```
Bean Validation can't express "exactly one of these groups is
required" cleanly with field-level annotations, so `OrderController`
validates this manually at the top of `checkout()`:
- `HOME` and any of the five address fields blank → 400 with a clear
  message.
- `RELAY_POINT` and any of the six relay fields blank → 400.

Migration: both new `Order` columns and `Game.weightGrams` are added
via Hibernate's `ddl-auto=update` like every other column in this
project. `weightGrams` is `NOT NULL` with no DDL default — per the
lesson from the listing-discovery feature (`Listing.featured`
regression caught in its final review), any `NOT NULL` column added
without a `columnDefinition` default will fail `ddl-auto=update`
against a Postgres database that already has `Game` rows. Since the
local dev database already has demo games, this column needs
`columnDefinition = "integer not null default 200"` (200g as a
placeholder default for any pre-existing game rows, which the seller
can edit later) — matching the precedent already set by
`User.confirmed`.

`Order.deliveryMode` and `Order.shippingCost` are also `NOT NULL` new
columns — same treatment: `columnDefinition = "varchar(20) not null
default 'HOME'"` for `deliveryMode` and `columnDefinition = "numeric
not null default 0"` for `shippingCost`, since the local database
already has `Order` rows from earlier testing.

## Rate calculation

New `payment`-sibling package `shipping`, component
`ShippingRateCalculator` (no service layer in this codebase — this
matches `StripeClient`/`IgdbClient`, plain `@Component`s called
directly from controllers):

```java
public BigDecimal calculate(DeliveryMode mode, int totalWeightGrams)
```

A fixed, hardcoded rate table (indicative pricing gathered from public
Mondial Relay rate sheets — not a live negotiated merchant rate, which
is never publicly exposed regardless of contract status):

| Up to weight | Point Relais | Home |
|---|---|---|
| 250g | 4.15€ | 4.99€ |
| 1kg | 5.99€ | 6.99€ |
| 3kg | 6.99€ | 7.99€ |
| 5kg | 7.99€ | 8.99€ |
| 10kg | 9.49€ | 11.99€ |
| 15kg | 11.49€ | 14.99€ |
| 20kg+ (fallback) | 13.49€ | 17.99€ |

Implementation: an ordered `List` of `(int maxGrams, BigDecimal
relayPrice, BigDecimal homePrice)` tiers declared as a `private static
final` field; `calculate` finds the first tier where `totalWeightGrams
<= maxGrams`, else returns the last tier's price. `totalWeightGrams`
comes from summing `listing.getGame().getWeightGrams()` across an
order's items.

**Where it's applied**: in `OrderController#createOrderForShop`
(called once per shop during checkout, since the cart is already
split by shop) — after building `orderItems` for that shop, sum their
games' weights, call `shippingRateCalculator.calculate(request.deliveryMode(),
totalWeight)`, set it on the `Order` before saving.

## Mondial Relay Point Relais search

New package `shipping`, component `MondialRelayClient`:

```java
public List<RelayPointResult> search(String postCode, String country)
```

Calls Mondial Relay's SOAP Web Service
(`https://api.mondialrelay.com/Web_Services.asmx`), method
`WSI4_PointRelais_Recherche`, following the same lightweight pattern
as `IgdbClient` (plain `RestClient`, hand-built request body, no
generated SOAP stubs / no new heavy dependency like `spring-ws`):

- Build the SOAP 1.1 XML envelope as a formatted `String` template.
  `WSI4_PointRelais_Recherche` takes: `Enseigne` (from config), `Pays`
  (the `country` param), `CP` (the `postCode` param), and these left
  blank/zero since this feature doesn't need them: `Ville`,
  `Latitude`, `Longitude`, `Taille`, `Poids`, `Action`, `DelaiEnvoi`,
  `RayonRecherche` (leave blank — API defaults to a sensible search
  radius), `TypeActivite`, `NombreResultats` (leave blank — API
  defaults to returning up to 30 points), and `Security` (computed,
  see below).
- Compute the `Security` parameter: concatenate the method's
  parameters in Mondial Relay's documented order, append the private
  key, MD5-hash the result, uppercase-hex-encode it. A small private
  helper using `java.security.MessageDigest`.
- POST the envelope, parse the XML response with
  `javax.xml.parsers.DocumentBuilder` (JDK built-in, no new
  dependency) into a `List<RelayPointResult>` (`record
  RelayPointResult(String id, String name, String street, String
  postCode, String city, String country)`).
- On any SOAP fault or unparseable response, log a warning and return
  an empty list — mirrors `IgdbClient.searchGame`'s
  fail-soft-return-`Optional.empty()` pattern, so a Mondial Relay
  outage doesn't crash the endpoint.

Credentials via `@Value`, configured in `application.yaml`:
```yaml
mondialrelay:
  enseigne: ${MONDIALRELAY_ENSEIGNE:BDTEST13}
  private-key: ${MONDIALRELAY_PRIVATE_KEY:TestAPI1key}
```

New `ShippingController` (`/shipping`):
```java
@GetMapping("/relay-points")
public ResponseEntity<List<RelayPointResult>> findRelayPoints(
        @RequestParam String postCode,
        @RequestParam(defaultValue = "BE") String country
)
```
Public endpoint (added to `SecurityConfig`'s `GET` permit-all list
alongside `/listings/**` etc.) — lets a future frontend show the
relay-point picker anywhere in the funnel, not just post-login,
matching the existing `GET /listings/**` precedent.

No separate shipping-cost "quote" endpoint — the computed
`shippingCost` is only visible in the checkout response (`OrderResponse`
gains a `deliveryMode`, `relayPoint`, and `shippingCost` field). A
pre-checkout price preview is a reasonable future addition once a
frontend exists to consume it, not built now.

## Stripe integration

`StripeClient.createCheckoutSession` signature changes from
`createCheckoutSession(List<OrderItem> items)` to
`createCheckoutSession(List<OrderItem> items, List<Order> orders)`.

After building the existing per-item line items, it adds one
additional line item per order in `orders`:
- Name: `"Livraison"` if `orders.size() == 1`, else `"Livraison - " +
  order.getShop().getName()` (disambiguates when a cart spans
  multiple shops/shipments).
- Amount: `order.getShippingCost()`.

`OrderController#checkout` already has `createdOrders` (the list this
new parameter needs) available at the point it calls
`stripeClient.createCheckoutSession(...)` — passing it through is a
one-line change at the call site.

## Testing

**`ShippingRateCalculatorTest`** — plain JUnit 5, no Spring context.
Boundary cases: exactly at a tier threshold (e.g. exactly 250g),
just under/over each threshold, both `HOME` and `RELAY_POINT`, and the
20kg+ fallback tier.

**`ShippingControllerTest`** — `@SpringBootTest` + `MockMvc` +
`@MockitoBean MondialRelayClient` (replaces the real client so the
test suite never makes a real network call to Mondial Relay's test
API — fast, deterministic, consistent with how `ListingDiscoveryControllerTest`
avoids depending on external seed data). Verifies `GET
/shipping/relay-points` returns the mocked client's canned list,
shaped correctly as JSON.

**`OrderControllerCheckoutTest`** (new — `OrderController` currently
has zero test coverage of any kind) — `@SpringBootTest` + `MockMvc` +
`@MockitoBean StripeClient` (stubbed to return a canned
`CheckoutSession`, so no real Stripe network call happens; the test
profile's `stripe.secret-key` is empty anyway, which would otherwise
make a real, doomed-to-fail call to Stripe's API). Fixtures: a buyer
`User` + `Cart` with items, one or two seller `Shop`s each owning
`Listing`s whose `Game`s have known `weightGrams`, built directly via
repositories in a `@BeforeEach`, matching the fixture style already
established in `ListingDiscoveryControllerTest`.

Cases:
- `HOME` checkout: `Order.shippingCost` matches the rate table for the
  cart's total weight; `Order.shippingAddress` populated;
  `Order.relayPoint` is null.
- `RELAY_POINT` checkout: `Order.relayPoint` populated with the
  request's snapshot fields; `Order.shippingAddress` is null;
  `shippingCost` uses the (cheaper) Point Relais tier for the same
  weight.
- Multi-shop cart (two shops): two `Order`s created, each with its
  own independently-computed `shippingCost` based on only that shop's
  items' weight — not a single cart-wide total.
- `HOME` with a blank address field → 400.
- `RELAY_POINT` with a blank `relayPointId` → 400.

Each test asserts against `StripeClient`'s captured invocation
(`ArgumentCaptor` on `createCheckoutSession`) to confirm the right
number of shipping line items and amounts were passed, in addition to
asserting the persisted `Order` state.
