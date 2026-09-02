# Stripe Payment Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the manual seller-driven `PENDING → PAID` transition with a real Stripe Checkout payment flow, including a signed webhook and an automatic cleanup job for abandoned checkouts.

**Architecture:** `POST /orders/checkout` keeps creating `Order`/`OrderItem` rows exactly as today, then asks a new `StripeClient` to open one Stripe Checkout Session covering the whole cart and stores its ID on every order just created. A new `PaymentController` webhook, verified by Stripe's signature scheme, is the only path that can flip an order to `PAID`. A new `PaymentScheduler` periodically cancels `PENDING` orders that have sat unpaid too long, reusing the existing "release listings back to `AVAILABLE`" pattern already used by `OrderController.cancel()`.

**Tech Stack:** Spring Boot 4.1.1, Java 25, `stripe-java` SDK, PostgreSQL, existing project conventions (no service layer — controllers/schedulers talk to repositories directly).

---

## Prerequisites (manual, one-time, before Task 5)

Stripe Checkout Session creation calls the real Stripe API, so a Stripe test-mode secret key is required before Task 5's live test can run:

1. Sign up (or log in) at https://dashboard.stripe.com — no business verification needed to use test mode.
2. Make sure **Test mode** is toggled on (top-right of the dashboard).
3. Go to **Developers → API keys**, copy the **Secret key** (`sk_test_...`).
4. For the webhook secret: no real Stripe webhook endpoint is needed for local testing. Since we verify signatures ourselves in `PaymentController`, we can pick any string, e.g. `whsec_local_dev_secret`, and use that exact same value both as `STRIPE_WEBHOOK_SECRET` and when hand-crafting test webhook calls in Task 7. Use the real dashboard-issued secret only when a production webhook endpoint is eventually configured.

These become environment variables passed to the app at launch:
- `STRIPE_SECRET_KEY=sk_test_...`
- `STRIPE_WEBHOOK_SECRET=whsec_local_dev_secret`

---

### Task 1: Add the Stripe SDK dependency and configuration

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yaml`

- [x] **Step 1: Add the `stripe-java` dependency**

In `pom.xml`, inside the existing `<dependencies>` block, add (right after the `postgresql` dependency, before `lombok`):

```xml
        <dependency>
            <groupId>com.stripe</groupId>
            <artifactId>stripe-java</artifactId>
            <version>29.4.0</version>
        </dependency>
```

If Maven can't resolve `29.4.0` (Stripe releases often), check https://mvnrepository.com/artifact/com.stripe/stripe-java for the current latest `29.x`/`30.x` version and use that instead — this is the only version-pin in the plan expected to need adjustment.

- [x] **Step 2: Add Stripe and scheduler configuration**

In `src/main/resources/application.yaml`, add two new top-level sections (after the existing `igdb:` block, before `spring:`):

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

`payment.pending-expiration-minutes` and `payment.expiration-check-rate-ms` are overridable via env vars specifically so Task 8's live test can use short values (minutes/seconds) instead of waiting on the real 30-minute/15-minute production defaults.

- [x] **Step 3: Verify the project still builds**

Run via IntelliJ (this resolves the new dependency through the IDE's own Maven infra, which has previously succeeded where sandboxed `mvn` hit certificate errors on fresh downloads):

Use `mcp__idea__build_project` with `projectPath: "C:\\Users\\lemet\\Documents\\Technifutur\\new-game-plus"`.

Expected: build succeeds, no errors. If it fails with a dependency resolution error, revisit the version in Step 1.

- [x] **Step 4: Commit**

```bash
git add pom.xml src/main/resources/application.yaml
git commit -m "Add Stripe SDK dependency and payment configuration"
```

---

### Task 2: Track the Stripe session on `Order`

**Files:**
- Modify: `src/main/java/be/technifutur/newgameplus/entities/Order.java`
- Modify: `src/main/java/be/technifutur/newgameplus/repositories/OrderRepository.java`

- [x] **Step 1: Add the `stripeSessionId` field**

In `Order.java`, add this field right after `shippingAddress` and before `createdAt`:

```java
    @Column(name = "stripe_session_id")
    private String stripeSessionId;
```

- [x] **Step 2: Add repository lookups**

Replace the full contents of `OrderRepository.java` with:

```java
package be.technifutur.newgameplus.repositories;

import be.technifutur.newgameplus.entities.Order;
import be.technifutur.newgameplus.entities.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByBuyerId(UUID buyerId);

    List<Order> findByShopId(UUID shopId);

    List<Order> findByStripeSessionId(String stripeSessionId);

    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime cutoff);
}
```

- [x] **Step 3: Verify the project builds**

Use `mcp__idea__build_project` with `projectPath: "C:\\Users\\lemet\\Documents\\Technifutur\\new-game-plus"`.
Expected: build succeeds (Hibernate will add the `stripe_session_id` column automatically on next app start, via `ddl-auto: update`).

- [x] **Step 4: Commit**

```bash
git add src/main/java/be/technifutur/newgameplus/entities/Order.java src/main/java/be/technifutur/newgameplus/repositories/OrderRepository.java
git commit -m "Add stripeSessionId to Order and matching repository queries"
```

---

### Task 3: `StripeClient` — create a Checkout Session

**Files:**
- Create: `src/main/java/be/technifutur/newgameplus/payment/StripeClient.java`

- [x] **Step 1: Write the client**

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

import java.math.BigDecimal;
import java.util.List;

@Component
public class StripeClient {

    @Value("${stripe.secret-key}")
    private String secretKey;

    @Value("${stripe.success-url}")
    private String successUrl;

    @Value("${stripe.cancel-url}")
    private String cancelUrl;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }

    public CheckoutSession createCheckoutSession(List<OrderItem> items) throws StripeException {
        SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl);

        for (OrderItem item : items) {
            long unitAmount = item.getPrice().multiply(BigDecimal.valueOf(100)).longValueExact();

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

- [x] **Step 2: Verify the project builds**

Use `mcp__idea__build_project` with `projectPath: "C:\\Users\\lemet\\Documents\\Technifutur\\new-game-plus"`.
Expected: build succeeds.

- [x] **Step 3: Commit**

```bash
git add src/main/java/be/technifutur/newgameplus/payment/StripeClient.java
git commit -m "Add StripeClient for creating Checkout Sessions"
```

*(Follow-up fix commit `96a0359`: rounding safety in `unitAmount` conversion + Javadoc documenting the `@Transactional`/`rollbackFor` contract, per code-quality review.)*

---

### Task 4: `CheckoutResponse` DTO

**Files:**
- Create: `src/main/java/be/technifutur/newgameplus/dto/response/CheckoutResponse.java`

- [x] **Step 1: Write the DTO**

```java
package be.technifutur.newgameplus.dto.response;

import java.util.List;

public record CheckoutResponse(
        List<OrderResponse> orders,
        String checkoutUrl
) {
}
```

- [x] **Step 2: Verify the project builds**

Use `mcp__idea__build_project` with `projectPath: "C:\\Users\\lemet\\Documents\\Technifutur\\new-game-plus"`.
Expected: build succeeds.

- [x] **Step 3: Commit**

```bash
git add src/main/java/be/technifutur/newgameplus/dto/response/CheckoutResponse.java
git commit -m "Add CheckoutResponse DTO"
```

---

### Task 5: Wire Stripe into `POST /orders/checkout`

**Files:**
- Modify: `src/main/java/be/technifutur/newgameplus/controller/OrderController.java`

- [x] **Step 1: Update imports and fields**

Find this import block at the top of `OrderController.java`:

```java
import be.technifutur.newgameplus.dto.request.CheckoutRequest;
import be.technifutur.newgameplus.dto.request.UpdateOrderStatusRequest;
import be.technifutur.newgameplus.dto.response.OrderResponse;
import be.technifutur.newgameplus.entities.*;
import be.technifutur.newgameplus.repositories.*;
import be.technifutur.newgameplus.security.JwtUtils;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
```

Replace it with:

```java
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
```

Find the field declarations:

```java
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
```

Replace with:

```java
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final StripeClient stripeClient;
```

- [x] **Step 2: Replace `checkout()` and `createOrderForShop()`**

Find this whole block (the `checkout` method through the end of `createOrderForShop`):

```java
    @PostMapping("/checkout")
    @Transactional
    public ResponseEntity<List<OrderResponse>> checkout(
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

        List<OrderResponse> responses = itemsByShop.entrySet().stream()
                .map(entry -> createOrderForShop(buyer, entry.getKey(), entry.getValue(), request))
                .toList();

        cartItemRepository.deleteByCartId(cart.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(responses);
    }

    private OrderResponse createOrderForShop(User buyer, Shop shop, List<CartItem> items, CheckoutRequest request) {
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

        return OrderResponse.fromOrder(savedOrder, orderItems);
    }
}
```

Replace it with:

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

    private record OrderWithItems(Order order, List<OrderItem> items) {
    }
}
```

Note: a checked `StripeException` thrown from `createCheckoutSession` is wrapped into a `ResponseStatusException` (unchecked), so Spring's default `@Transactional` rollback rule (rollback on unchecked exceptions) still rolls back all the `Order`/`OrderItem`/`Listing` changes made earlier in the method — no extra `rollbackFor` needed.

- [x] **Step 3: Verify the project builds**

Use `mcp__idea__build_project` with `projectPath: "C:\\Users\\lemet\\Documents\\Technifutur\\new-game-plus"`.
Expected: build succeeds.

- [ ] **Step 4: Live test — checkout returns a Stripe checkout URL** *(pending — waiting on a Stripe test-mode secret key from the user)*

Launch the app with the Stripe test key from Prerequisites:

Use `mcp__idea__execute_run_configuration` with `configurationName: "NewGamePlusApplication"`, `waitForExit: false`, `envs: { "STRIPE_SECRET_KEY": "sk_test_...", "STRIPE_WEBHOOK_SECRET": "whsec_local_dev_secret" }`.

Wait ~12 seconds for startup, then run this self-contained script (registers a seller+buyer, confirms them directly in the DB since there's no mail server to click a confirmation link, creates a shop and a listing, then checks out):

```powershell
$base = "http://localhost:8080"
$rand = Get-Random

Invoke-RestMethod -Uri "$base/auth/register" -Method Post -Body (@{ username="seller_$rand"; email="seller_$rand@test.com"; password="Password123!" } | ConvertTo-Json) -ContentType "application/json" | Out-Null
Invoke-RestMethod -Uri "$base/auth/register" -Method Post -Body (@{ username="buyer_$rand"; email="buyer_$rand@test.com"; password="Password123!" } | ConvertTo-Json) -ContentType "application/json" | Out-Null

$env:PGPASSWORD = "postgres"
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d new_game_plus -c "UPDATE user_ SET confirmed = true WHERE email IN ('seller_$rand@test.com','buyer_$rand@test.com');"

$sellerAuth = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -Body (@{ email="seller_$rand@test.com"; password="Password123!" } | ConvertTo-Json) -ContentType "application/json"
$buyerAuth  = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -Body (@{ email="buyer_$rand@test.com"; password="Password123!" } | ConvertTo-Json) -ContentType "application/json"
$sellerH = @{ Authorization = "Bearer $($sellerAuth.token)" }
$buyerH  = @{ Authorization = "Bearer $($buyerAuth.token)" }

Invoke-RestMethod -Uri "$base/shops" -Method Post -Headers $sellerH -Body (@{ name="TestShop_$rand"; description="test" } | ConvertTo-Json) -ContentType "application/json" | Out-Null
$sellerAuth = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -Body (@{ email="seller_$rand@test.com"; password="Password123!" } | ConvertTo-Json) -ContentType "application/json"
$sellerH = @{ Authorization = "Bearer $($sellerAuth.token)" }

$game = (Invoke-RestMethod -Uri "$base/games?page=0&size=1" -Method Get).content[0]
$listing = Invoke-RestMethod -Uri "$base/listings" -Method Post -Headers $sellerH -Body (@{ gameId=$game.id; price=19.99 } | ConvertTo-Json) -ContentType "application/json"

Invoke-RestMethod -Uri "$base/cart/items" -Method Post -Headers $buyerH -Body (@{ listingId=$listing.id; quantity=1 } | ConvertTo-Json) -ContentType "application/json" | Out-Null
$addr = @{ street="Rue Test"; streetNumber="1"; postCode="4000"; city="Liege"; country="Belgium" } | ConvertTo-Json
$checkout = Invoke-RestMethod -Uri "$base/orders/checkout" -Method Post -Headers $buyerH -Body $addr -ContentType "application/json"

$orderId = $checkout.orders[0].id
$sessionId = (($checkout.checkoutUrl -split '/')[-1] -split '#')[0]

Write-Output "checkoutUrl: $($checkout.checkoutUrl)"
Write-Output "orderId: $orderId"
Write-Output "sessionId: $sessionId"

# Persist for later tasks (this PowerShell session's variables won't survive to the next tool call)
$orderId       | Out-File -FilePath "$env:TEMP\ngp_order_id.txt" -NoNewline
$sessionId     | Out-File -FilePath "$env:TEMP\ngp_session_id.txt" -NoNewline
$sellerAuth.token | Out-File -FilePath "$env:TEMP\ngp_seller_token.txt" -NoNewline
```

Expected: `checkoutUrl` starts with `https://checkout.stripe.com/`, `sessionId` starts with `cs_test_`. Confirm it's stored on the order:

```powershell
$env:PGPASSWORD = "postgres"
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d new_game_plus -c "SELECT id, status, stripe_session_id FROM ""order"" WHERE id = '$orderId';"
```

Expected: `stripe_session_id` matches `$sessionId`, `status = PENDING`.

- [x] **Step 5: Commit**

```bash
git add src/main/java/be/technifutur/newgameplus/controller/OrderController.java
git commit -m "Create a Stripe Checkout Session on order checkout"
```

---

### Task 6: Block the manual `PENDING → PAID` transition

**Files:**
- Modify: `src/main/java/be/technifutur/newgameplus/controller/OrderController.java`

- [ ] **Step 1: Update `assertValidTransition`**

Find:

```java
    private void assertValidTransition(OrderStatus current, OrderStatus next) {
        boolean allowed = switch (current) {
            case PENDING -> next == OrderStatus.PAID || next == OrderStatus.CANCELLED;
            case PAID -> next == OrderStatus.SHIPPED || next == OrderStatus.CANCELLED;
            case SHIPPED -> next == OrderStatus.DELIVERED || next == OrderStatus.CANCELLED;
            case DELIVERED, CANCELLED -> false;
        };
```

Replace with:

```java
    private void assertValidTransition(OrderStatus current, OrderStatus next) {
        boolean allowed = switch (current) {
            case PENDING -> next == OrderStatus.CANCELLED;
            case PAID -> next == OrderStatus.SHIPPED || next == OrderStatus.CANCELLED;
            case SHIPPED -> next == OrderStatus.DELIVERED || next == OrderStatus.CANCELLED;
            case DELIVERED, CANCELLED -> false;
        };
```

(`PENDING → PAID` is removed: only the Stripe webhook, via direct repository access, may make that transition now.)

- [ ] **Step 2: Verify the project builds**

Use `mcp__idea__build_project` with `projectPath: "C:\\Users\\lemet\\Documents\\Technifutur\\new-game-plus"`.
Expected: build succeeds.

- [ ] **Step 3: Live test — manual PAID transition is rejected**

With the app still running from Task 5, reload the order id and seller token saved to disk in that task's Step 4:

```powershell
$orderId = (Get-Content "$env:TEMP\ngp_order_id.txt" -Raw).Trim()
$sellerToken = (Get-Content "$env:TEMP\ngp_seller_token.txt" -Raw).Trim()
$sellerH = @{ Authorization = "Bearer $sellerToken" }

try {
    Invoke-RestMethod -Uri "http://localhost:8080/orders/$orderId/status" -Method Patch -Headers $sellerH -Body (@{ status="PAID" } | ConvertTo-Json) -ContentType "application/json"
    Write-Output "UNEXPECTED OK"
} catch { Write-Output "expected fail: $($_.Exception.Response.StatusCode)" }
```

Expected: `expected fail: Conflict` (409).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/be/technifutur/newgameplus/controller/OrderController.java
git commit -m "Block manual PENDING to PAID transition, Stripe webhook is now the only path"
```

---

### Task 7: Stripe webhook — confirm payment

**Files:**
- Create: `src/main/java/be/technifutur/newgameplus/payment/PaymentController.java`
- Modify: `src/main/java/be/technifutur/newgameplus/security/SecurityConfig.java`

- [ ] **Step 1: Write the webhook controller**

```java
package be.technifutur.newgameplus.payment;

import be.technifutur.newgameplus.entities.Order;
import be.technifutur.newgameplus.entities.OrderStatus;
import be.technifutur.newgameplus.repositories.OrderRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Webhook Stripe")
public class PaymentController {

    private final OrderRepository orderRepository;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signature
    ) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature invalide: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if ("checkout.session.completed".equals(event.getType())) {
            event.getDataObjectDeserializer().getObject()
                    .filter(Session.class::isInstance)
                    .map(Session.class::cast)
                    .ifPresent(this::markOrdersPaid);
        }

        return ResponseEntity.ok().build();
    }

    private void markOrdersPaid(Session session) {
        List<Order> orders = orderRepository.findByStripeSessionId(session.getId());
        if (orders.isEmpty()) {
            log.warn("Webhook Stripe reçu pour une session inconnue: {}", session.getId());
            return;
        }

        orders.stream()
                .filter(order -> order.getStatus() == OrderStatus.PENDING)
                .forEach(order -> {
                    order.setStatus(OrderStatus.PAID);
                    orderRepository.save(order);
                });
    }
}
```

- [ ] **Step 2: Allow unauthenticated access to the webhook**

In `SecurityConfig.java`, find:

```java
                        .requestMatchers("/error", "/favicon.ico").permitAll()
                        .requestMatchers("/auth/**").permitAll()
```

Replace with:

```java
                        .requestMatchers("/error", "/favicon.ico").permitAll()
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/payments/webhook").permitAll()
```

- [ ] **Step 3: Verify the project builds**

Use `mcp__idea__build_project` with `projectPath: "C:\\Users\\lemet\\Documents\\Technifutur\\new-game-plus"`.
Expected: build succeeds.

- [ ] **Step 4: Live test — webhook confirms payment**

App still running with `STRIPE_WEBHOOK_SECRET=whsec_local_dev_secret` (from Task 5's launch). Reload the session id saved to disk in Task 5's Step 4:

```powershell
$sessionId = (Get-Content "$env:TEMP\ngp_session_id.txt" -Raw).Trim()
$secret = "whsec_local_dev_secret"
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$payload = '{"id":"evt_test_1","object":"event","type":"checkout.session.completed","data":{"object":{"id":"' + $sessionId + '","object":"checkout.session"}}}'
$signedPayload = "$timestamp.$payload"
$hmac = New-Object System.Security.Cryptography.HMACSHA256
$hmac.Key = [System.Text.Encoding]::UTF8.GetBytes($secret)
$hash = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($signedPayload))
$hex = ($hash | ForEach-Object { $_.ToString("x2") }) -join ""
$sigHeader = "t=$timestamp,v1=$hex"

Invoke-RestMethod -Uri "http://localhost:8080/payments/webhook" -Method Post -Body $payload -ContentType "application/json" -Headers @{ "Stripe-Signature" = $sigHeader }
```

Expected: `200 OK`, no error. Then verify the order moved to `PAID`:

```powershell
$env:PGPASSWORD = "postgres"
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d new_game_plus -c "SELECT id, status FROM ""order"" WHERE stripe_session_id = '$sessionId';"
```

Expected: `status = PAID` for every order sharing that session.

- [ ] **Step 5: Live test — idempotent replay**

Re-run the exact same PowerShell block from Step 4 (same `$timestamp`/`$payload`/`$sigHeader` — or regenerate, doesn't matter as long as `data.object.id` is the same session).

Expected: `200 OK` again, order stays `PAID` (no error, no duplicate side effects — confirm via the same `psql` query, status still `PAID`).

- [ ] **Step 6: Live test — unknown session and bad signature**

```powershell
# Unknown session id
$payload2 = '{"id":"evt_test_2","object":"event","type":"checkout.session.completed","data":{"object":{"id":"cs_test_does_not_exist","object":"checkout.session"}}}'
$timestamp2 = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$signedPayload2 = "$timestamp2.$payload2"
$hash2 = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($signedPayload2))
$hex2 = ($hash2 | ForEach-Object { $_.ToString("x2") }) -join ""
Invoke-RestMethod -Uri "http://localhost:8080/payments/webhook" -Method Post -Body $payload2 -ContentType "application/json" -Headers @{ "Stripe-Signature" = "t=$timestamp2,v1=$hex2" }

# Bad signature
try {
    Invoke-RestMethod -Uri "http://localhost:8080/payments/webhook" -Method Post -Body $payload2 -ContentType "application/json" -Headers @{ "Stripe-Signature" = "t=$timestamp2,v1=deadbeef" }
    Write-Output "UNEXPECTED OK"
} catch { Write-Output "expected fail: $($_.Exception.Response.StatusCode)" }
```

Expected: first call → `200 OK` (logged as a warning server-side, order state unaffected since the session doesn't exist). Second call → `expected fail: BadRequest` (400).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/be/technifutur/newgameplus/payment/PaymentController.java src/main/java/be/technifutur/newgameplus/security/SecurityConfig.java
git commit -m "Add Stripe webhook to confirm payment and mark orders PAID"
```

---

### Task 8: Expire abandoned `PENDING` orders

**Files:**
- Modify: `src/main/java/be/technifutur/newgameplus/NewGamePlusApplication.java`
- Create: `src/main/java/be/technifutur/newgameplus/payment/PaymentScheduler.java`

- [ ] **Step 1: Enable scheduling**

In `NewGamePlusApplication.java`, replace the full file with:

```java
package be.technifutur.newgameplus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NewGamePlusApplication {

    public static void main(String[] args) {
        SpringApplication.run(NewGamePlusApplication.class, args);
    }

}
```

- [ ] **Step 2: Write the scheduler**

```java
package be.technifutur.newgameplus.payment;

import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.ListingStatus;
import be.technifutur.newgameplus.entities.Order;
import be.technifutur.newgameplus.entities.OrderItem;
import be.technifutur.newgameplus.entities.OrderStatus;
import be.technifutur.newgameplus.repositories.ListingRepository;
import be.technifutur.newgameplus.repositories.OrderItemRepository;
import be.technifutur.newgameplus.repositories.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentScheduler {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ListingRepository listingRepository;

    @Value("${payment.pending-expiration-minutes}")
    private long expirationMinutes;

    @Scheduled(fixedRateString = "${payment.expiration-check-rate-ms}")
    @Transactional
    public void cancelStalePendingOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(expirationMinutes);
        List<Order> staleOrders = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, cutoff);

        for (Order order : staleOrders) {
            List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
            for (OrderItem item : items) {
                Listing listing = item.getListing();
                listing.setStatus(ListingStatus.AVAILABLE);
                listingRepository.save(listing);
            }
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            log.info("Commande {} annulée automatiquement (PENDING depuis plus de {} min)", order.getId(), expirationMinutes);
        }
    }
}
```

- [ ] **Step 3: Verify the project builds**

Use `mcp__idea__build_project` with `projectPath: "C:\\Users\\lemet\\Documents\\Technifutur\\new-game-plus"`.
Expected: build succeeds.

- [ ] **Step 4: Live test — stale order gets auto-cancelled**

Launch with a short expiration window so the test doesn't take 30 real minutes:

Use `mcp__idea__execute_run_configuration` with `configurationName: "NewGamePlusApplication"`, `waitForExit: false`, `envs: { "STRIPE_SECRET_KEY": "sk_test_...", "STRIPE_WEBHOOK_SECRET": "whsec_local_dev_secret", "PAYMENT_PENDING_EXPIRATION_MINUTES": "1", "PAYMENT_EXPIRATION_CHECK_RATE_MS": "20000" }`.

Wait ~12 seconds for startup, then create a fresh order with this self-contained script (same shape as Task 5 Step 4's script, repeated here so this task doesn't depend on Task 5's leftover state):

```powershell
$base = "http://localhost:8080"
$rand = Get-Random

Invoke-RestMethod -Uri "$base/auth/register" -Method Post -Body (@{ username="seller_$rand"; email="seller_$rand@test.com"; password="Password123!" } | ConvertTo-Json) -ContentType "application/json" | Out-Null
Invoke-RestMethod -Uri "$base/auth/register" -Method Post -Body (@{ username="buyer_$rand"; email="buyer_$rand@test.com"; password="Password123!" } | ConvertTo-Json) -ContentType "application/json" | Out-Null

$env:PGPASSWORD = "postgres"
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d new_game_plus -c "UPDATE user_ SET confirmed = true WHERE email IN ('seller_$rand@test.com','buyer_$rand@test.com');"

$sellerAuth = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -Body (@{ email="seller_$rand@test.com"; password="Password123!" } | ConvertTo-Json) -ContentType "application/json"
$buyerAuth  = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -Body (@{ email="buyer_$rand@test.com"; password="Password123!" } | ConvertTo-Json) -ContentType "application/json"
$sellerH = @{ Authorization = "Bearer $($sellerAuth.token)" }
$buyerH  = @{ Authorization = "Bearer $($buyerAuth.token)" }

Invoke-RestMethod -Uri "$base/shops" -Method Post -Headers $sellerH -Body (@{ name="TestShop_$rand"; description="test" } | ConvertTo-Json) -ContentType "application/json" | Out-Null
$sellerAuth = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -Body (@{ email="seller_$rand@test.com"; password="Password123!" } | ConvertTo-Json) -ContentType "application/json"
$sellerH = @{ Authorization = "Bearer $($sellerAuth.token)" }

$game = (Invoke-RestMethod -Uri "$base/games?page=0&size=1" -Method Get).content[0]
$listing = Invoke-RestMethod -Uri "$base/listings" -Method Post -Headers $sellerH -Body (@{ gameId=$game.id; price=19.99 } | ConvertTo-Json) -ContentType "application/json"

Invoke-RestMethod -Uri "$base/cart/items" -Method Post -Headers $buyerH -Body (@{ listingId=$listing.id; quantity=1 } | ConvertTo-Json) -ContentType "application/json" | Out-Null
$addr = @{ street="Rue Test"; streetNumber="1"; postCode="4000"; city="Liege"; country="Belgium" } | ConvertTo-Json
$checkout = Invoke-RestMethod -Uri "$base/orders/checkout" -Method Post -Headers $buyerH -Body $addr -ContentType "application/json"

$orderId = $checkout.orders[0].id
$listingId = $listing.id
Write-Output "orderId: $orderId"
Write-Output "listingId: $listingId"

$orderId   | Out-File -FilePath "$env:TEMP\ngp_stale_order_id.txt" -NoNewline
$listingId | Out-File -FilePath "$env:TEMP\ngp_stale_listing_id.txt" -NoNewline
```

Then wait past the 1-minute expiration plus one 20-second scheduler tick, and check the outcome:

```powershell
Start-Sleep -Seconds 90

$orderId = (Get-Content "$env:TEMP\ngp_stale_order_id.txt" -Raw).Trim()
$listingId = (Get-Content "$env:TEMP\ngp_stale_listing_id.txt" -Raw).Trim()

$env:PGPASSWORD = "postgres"
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d new_game_plus -c "SELECT status FROM ""order"" WHERE id = '$orderId';"
Invoke-RestMethod -Uri "http://localhost:8080/listings/$listingId" -Method Get
```

Expected: order `status = CANCELLED`, listing `status = AVAILABLE`.

Stop the app afterward (`Stop-Process` on its PID, as done throughout this project's testing).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/be/technifutur/newgameplus/NewGamePlusApplication.java src/main/java/be/technifutur/newgameplus/payment/PaymentScheduler.java
git commit -m "Auto-cancel abandoned PENDING orders and release their listings"
```

---

## Post-implementation notes

- Once this ships, the `/orders/checkout` response shape changes from `List<OrderResponse>` to `CheckoutResponse { orders, checkoutUrl }` — any frontend code calling this endpoint needs updating to read `.orders` and redirect the buyer to `.checkoutUrl`.
- Real carrier/shipping integration is a separate, not-yet-brainstormed spec.
- Refunds on `CANCELLED` orders that were already `PAID` are out of scope — today cancelling only releases the listing, it does not talk to Stripe about a refund. Revisit before any production use of real money.
