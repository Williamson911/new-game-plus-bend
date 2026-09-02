# Stripe Payment Integration — Design

Date: 2026-09-02
Status: Approved, ready for implementation plan

## Context

`new-game-plus` is a single-module Spring Boot marketplace (buyers/sellers, shops, listings, cart, checkout, orders, reviews) with no service layer — controllers use repositories directly. Orders currently move through a manual state machine (`PENDING → PAID → SHIPPED → DELIVERED`, `CANCELLED` from any non-terminal state), driven entirely by the seller calling `PATCH /orders/{id}/status`. There is no real payment provider yet: a seller can mark an order `PAID` by hand.

This spec introduces **Stripe Checkout** (hosted payment page) as the real payment mechanism for the `PENDING → PAID` transition, replacing the manual path. Real carrier/shipping integration is an intentionally separate spec, to be brainstormed after this one ships.

## Goals

- Buyers pay for their cart through Stripe's hosted Checkout page.
- A single Stripe payment covers the whole cart, even when the cart spans multiple shops (multiple `Order` rows).
- Orders move to `PAID` only via a verified Stripe webhook — never manually.
- Abandoned/unpaid `PENDING` orders are automatically cancelled and their listings released back to `AVAILABLE`.

## Non-goals

- Real carrier/shipping integration (separate spec).
- Multi-currency support (EUR only, hardcoded).
- Refunds/partial refunds (out of scope for this iteration).
- Stripe Elements / embedded card form (no frontend ready for it yet — hosted Checkout only).

## Flow overview

1. Buyer calls `POST /orders/checkout` as today: one `Order` is created per shop represented in the cart, each `PENDING`, and each `Listing` involved is marked `SOLD` immediately.
2. In the same transaction, the controller calls `StripeClient.createCheckoutSession(orders)` to create **one Stripe Checkout Session** covering the total of all orders just created (one Stripe line item per `OrderItem`, named after `Game.name`, priced from `Listing.price`).
3. Each created `Order` gets its `stripeSessionId` set to that session's ID.
4. The endpoint returns a `CheckoutResponse { orders: List<OrderResponse>, checkoutUrl: String }`. The frontend redirects the buyer to `checkoutUrl`.
5. The buyer pays on Stripe's hosted page, then is redirected to `success-url` or `cancel-url` (frontend routes, configurable).
6. Stripe calls `POST /payments/webhook` with `checkout.session.completed`. The handler verifies the Stripe signature, looks up all `Order` rows by `stripeSessionId`, and moves each from `PENDING` to `PAID` (idempotently — already-`PAID` orders are skipped, not re-processed or errored).
7. A scheduled job (`PaymentScheduler`, `@Scheduled(fixedRate = 15min)`) finds `PENDING` orders older than 30 minutes and cancels them, releasing their listings back to `AVAILABLE` — covering buyers who abandon the Stripe page.

If Stripe session creation fails (`StripeException`) during step 2, the whole `checkout` transaction rolls back (method stays `@Transactional`): no `Order` rows, no `Listing` status changes persist.

## Data model changes

- `Order.stripeSessionId: String` (nullable) — the Stripe Checkout Session ID. Used to (a) correlate the webhook event back to the affected orders and (b) provide idempotence / manual lookup in the Stripe dashboard.
- No changes to `Listing`, `Shop`, or any other entity.
- No new migration tooling needed — `spring.jpa.hibernate.ddl-auto: update` (already configured) adds the new `stripe_session_id` column automatically, consistent with how every other schema change in this project has been applied.
- Currency is not modeled in the database; it's a fixed `EUR` constant used only when building the Stripe session.

## API changes

### `POST /orders/checkout` (modified, `OrderController`)

- Same request body (`CheckoutRequest`) and same order-creation logic as today.
- After creating and flushing the `Order`/`OrderItem` rows (as today), calls a new `StripeClient.createCheckoutSession(List<Order> orders)`.
- Sets `stripeSessionId` on every created `Order` and saves them.
- Returns `201 CREATED` with a new `CheckoutResponse { List<OrderResponse> orders; String checkoutUrl }` instead of the current bare `List<OrderResponse>`.
- Remains `@Transactional`: any failure creating the Stripe session rolls back order creation and listing status changes.

### `POST /payments/webhook` (new, `PaymentController`)

- `permitAll` in `SecurityConfig` (Stripe cannot present a JWT) — security instead comes from verifying the `Stripe-Signature` header against `stripe.webhook-secret` using the Stripe SDK's signature verification.
- On `checkout.session.completed`:
  - Look up all `Order` rows where `stripeSessionId` equals the session ID from the event.
  - For each order still `PENDING`, set status to `PAID` and save. Orders already `PAID` are skipped silently (idempotent replay handling).
  - If no orders are found for the session ID, log a warning and still return `200 OK` (per Stripe convention: any non-2xx causes Stripe to retry indefinitely; an unknown session on our side is not something retrying will fix).
- On an invalid/missing signature: return `400 Bad Request` and log a warning (possible forged request).
- Any other event type: acknowledge with `200 OK` and ignore (no other event types are handled in this iteration).

### `PATCH /orders/{id}/status` (modified, `OrderController`)

- `assertValidTransition` no longer allows `PENDING → PAID` as a manually-requested transition. The webhook writes `PAID` directly via the repository, bypassing this method entirely. Manual transitions remain available for `PAID → SHIPPED`, `SHIPPED → DELIVERED`, and `* → CANCELLED` (non-terminal states) as today.

## New code structure

New package `payment` (sibling to `security`, `mailer`, `igdb`, `seed`):

- `StripeClient` — wraps Stripe SDK calls: building line items from a list of `Order`, creating the Checkout Session, exposing the resulting session ID and URL. Owns the `stripe.secret-key` config value and initializes the Stripe SDK's global API key on construction.
- `PaymentScheduler` — the `@Scheduled` job that cancels stale `PENDING` orders. Implements its own minimal "release listings + cancel order" logic directly against the repositories (does not call into `OrderController`), to avoid coupling a scheduler to a web controller. This duplicates a few lines already in `OrderController.releaseListings`/`cancel`, which is an accepted, deliberate tradeoff in a codebase with no service layer to share it through.
- `PaymentController` — hosts `POST /payments/webhook`.

## Configuration

New section in `application.yaml`, following the existing pattern used for IGDB (env var with empty/derived default, nothing sensitive committed):

```yaml
stripe:
  secret-key: ${STRIPE_SECRET_KEY:}
  webhook-secret: ${STRIPE_WEBHOOK_SECRET:}
  success-url: ${STRIPE_SUCCESS_URL:${app.frontend-url}/checkout/success}
  cancel-url: ${STRIPE_CANCEL_URL:${app.frontend-url}/checkout/cancel}
```

New Maven dependency: `com.stripe:stripe-java`.

Stripe test-mode keys (`sk_test_...`, `whsec_...`) come from the developer's own Stripe account and are supplied via environment variables only, matching how `IGDB_CLIENT_SECRET` and `MAIL_PASSWORD` are already handled.

## Error handling

- Stripe session creation failure (`StripeException`): the `checkout` transaction rolls back entirely — no orders, no listing changes persist. Endpoint returns `502 Bad Gateway` (external service failed).
- Webhook signature invalid: `400 Bad Request`, logged as a warning.
- Webhook references an unknown `stripeSessionId`: `200 OK` (to stop Stripe retries), logged as a warning.
- Webhook replay on an already-`PAID` order: silently skipped, no error, no re-processing.

## Testing plan (live, Stripe test mode)

1. Checkout with a multi-shop cart → verify one `Order` per shop, all `PENDING`, all sharing the same `stripeSessionId`, and a `checkoutUrl` is returned.
2. Complete payment on the Stripe test page (card `4242 4242 4242 4242`) using the Stripe CLI (`stripe listen --forward-to localhost:8080/payments/webhook`) to relay the webhook locally.
3. Verify all orders sharing that session move to `PAID`.
4. Replay the same webhook event manually → verify no error and no double processing.
5. Create a `PENDING` order, backdate its `createdAt` past 30 minutes (test-only DB tweak), run the scheduler manually → verify it's cancelled and its listing returns to `AVAILABLE`.
6. Attempt `PATCH /orders/{id}/status` with `{"status":"PAID"}` on a `PENDING` order → verify it's rejected (manual `PAID` transition blocked).

## Open items deliberately deferred

- Real carrier/shipping integration — separate spec, next.
- Refunds/cancellation-after-payment money flow — not handled; a `CANCELLED` order after `PAID` today only reverts the listing, it does not talk to Stripe about a refund. Acceptable for now since no real money moves in test mode; will need revisiting before any production use.
