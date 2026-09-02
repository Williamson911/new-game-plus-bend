package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.AddToCartRequest;
import be.technifutur.newgameplus.dto.response.CartResponse;
import be.technifutur.newgameplus.entities.Cart;
import be.technifutur.newgameplus.entities.CartItem;
import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.ListingStatus;
import be.technifutur.newgameplus.entities.User;
import be.technifutur.newgameplus.repositories.CartItemRepository;
import be.technifutur.newgameplus.repositories.CartRepository;
import be.technifutur.newgameplus.repositories.ListingRepository;
import be.technifutur.newgameplus.repositories.UserRepository;
import be.technifutur.newgameplus.security.JwtUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
@Tag(name = "Cart", description = "Panier de l'utilisateur connecté")
public class CartController {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<CartResponse> me(
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        List<CartItem> items = cartRepository.findByBuyerId(session.id())
                .map(cart -> cartItemRepository.findByCartId(cart.getId()))
                .orElse(List.of());

        return ResponseEntity.ok(CartResponse.fromCart(items));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @Valid @RequestBody AddToCartRequest request,
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        Listing listing = listingRepository.findById(request.listingId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing introuvable"));

        if (listing.getStatus() != ListingStatus.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette annonce n'est plus disponible");
        }
        if (listing.getShop().getOwner().getId().equals(session.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tu ne peux pas acheter ta propre annonce");
        }

        Cart cart = cartRepository.findByBuyerId(session.id())
                .orElseGet(() -> {
                    User buyer = userRepository.findById(session.id())
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
                    Cart newCart = new Cart();
                    newCart.setBuyer(buyer);
                    return cartRepository.save(newCart);
                });

        if (cartItemRepository.findByCartIdAndListingId(cart.getId(), listing.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce jeu est déjà dans ton panier");
        }

        CartItem item = new CartItem();
        item.setCart(cart);
        item.setListing(listing);
        cartItemRepository.save(item);

        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(CartResponse.fromCart(items));
    }

    @DeleteMapping("/items/{listingId}")
    public ResponseEntity<Void> removeItem(
            @PathVariable UUID listingId,
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        Cart cart = cartRepository.findByBuyerId(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Panier introuvable"));

        CartItem item = cartItemRepository.findByCartIdAndListingId(cart.getId(), listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cet article n'est pas dans ton panier"));

        cartItemRepository.delete(item);
        return ResponseEntity.noContent().build();
    }
}
