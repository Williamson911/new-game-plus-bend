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

    @GetMapping
    public ResponseEntity<List<OrderResponse>> myOrders(
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        List<OrderResponse> orders = orderRepository.findByBuyerId(session.id()).stream()
                .map(order -> OrderResponse.fromOrder(order, orderItemRepository.findByOrderId(order.getId())))
                .toList();
        return ResponseEntity.ok(orders);
    }

    @PreAuthorize("hasRole('SELLER')")
    @GetMapping("/shop")
    public ResponseEntity<List<OrderResponse>> shopOrders(
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        Shop shop = shopRepository.findByOwnerId(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tu n'as pas de boutique"));

        List<OrderResponse> orders = orderRepository.findByShopId(shop.getId()).stream()
                .map(order -> OrderResponse.fromOrder(order, orderItemRepository.findByOrderId(order.getId())))
                .toList();
        return ResponseEntity.ok(orders);
    }

    @PreAuthorize("hasRole('SELLER')")
    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<OrderResponse> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrderStatusRequest request,
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Commande introuvable"));

        if (!order.getShop().getOwner().getId().equals(session.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cette commande ne concerne pas ta boutique");
        }
        assertValidTransition(order.getStatus(), request.status());

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        if (request.status() == OrderStatus.CANCELLED) {
            releaseListings(items);
        }

        order.setStatus(request.status());
        orderRepository.save(order);

        return ResponseEntity.ok(OrderResponse.fromOrder(order, items));
    }

    @PatchMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<OrderResponse> cancel(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Commande introuvable"));

        if (!order.getBuyer().getId().equals(session.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cette commande ne t'appartient pas");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Impossible d'annuler une commande déjà " + order.getStatus());
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        releaseListings(items);

        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        return ResponseEntity.ok(OrderResponse.fromOrder(order, items));
    }

    private void releaseListings(List<OrderItem> items) {
        items.forEach(item -> {
            Listing listing = item.getListing();
            listing.setStatus(ListingStatus.AVAILABLE);
            listingRepository.save(listing);
        });
    }

    private void assertValidTransition(OrderStatus current, OrderStatus next) {
        boolean allowed = switch (current) {
            case PENDING -> next == OrderStatus.CANCELLED;
            case PAID -> next == OrderStatus.SHIPPED || next == OrderStatus.CANCELLED;
            case SHIPPED -> next == OrderStatus.DELIVERED || next == OrderStatus.CANCELLED;
            case DELIVERED, CANCELLED -> false;
        };

        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Impossible de passer une commande de " + current + " à " + next);
        }
    }

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

    private record OrderWithItems(Order order, List<OrderItem> items) {
    }
}
