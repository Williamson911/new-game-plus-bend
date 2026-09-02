package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.ShopRequest;
import be.technifutur.newgameplus.dto.response.ShopResponse;
import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.Role;
import be.technifutur.newgameplus.entities.Shop;
import be.technifutur.newgameplus.entities.User;
import be.technifutur.newgameplus.repositories.CartItemRepository;
import be.technifutur.newgameplus.repositories.ListingImageRepository;
import be.technifutur.newgameplus.repositories.ListingRepository;
import be.technifutur.newgameplus.repositories.OrderRepository;
import be.technifutur.newgameplus.repositories.ReviewRepository;
import be.technifutur.newgameplus.repositories.RoleRepository;
import be.technifutur.newgameplus.repositories.ShopRepository;
import be.technifutur.newgameplus.repositories.UserRepository;
import be.technifutur.newgameplus.security.JwtUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/shops")
@RequiredArgsConstructor
@Tag(name = "Shops", description = "Boutique du vendeur (création, consultation, suppression)")
public class ShopController {

    private final ShopRepository shopRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ListingRepository listingRepository;
    private final ListingImageRepository listingImageRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;

    @GetMapping("/me")
    public ResponseEntity<ShopResponse> me(
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        Shop shop = shopRepository.findByOwnerId(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tu n'as pas encore de boutique"));
        return ResponseEntity.ok(ShopResponse.fromShop(shop));
    }

    @PostMapping
    public ResponseEntity<ShopResponse> create(
            @Valid @RequestBody ShopRequest request,
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        if (shopRepository.existsByOwnerId(session.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà une boutique");
        }
        if (shopRepository.existsByName(request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce nom de boutique est déjà pris");
        }

        User owner = userRepository.findById(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));

        Shop shop = request.toShop();
        shop.setOwner(owner);
        shopRepository.save(shop);

        Role sellerRole = roleRepository.findByName("SELLER")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Rôle SELLER introuvable"));
        owner.getRoles().add(sellerRole);
        userRepository.save(owner);

        return ResponseEntity.status(HttpStatus.CREATED).body(ShopResponse.fromShop(shop));
    }

    @DeleteMapping
    @Transactional
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        Shop shop = shopRepository.findByOwnerId(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tu n'as pas de boutique"));

        if (!orderRepository.findByShopId(shop.getId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ta boutique a des commandes associées, suppression impossible");
        }
        if (!reviewRepository.findByShopId(shop.getId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ta boutique a des avis associés, suppression impossible");
        }

        List<Listing> listings = listingRepository.findByShopId(shop.getId());
        for (Listing listing : listings) {
            listingImageRepository.deleteByListingId(listing.getId());
            cartItemRepository.deleteByListingId(listing.getId());
        }
        listingRepository.deleteAll(listings);

        User owner = shop.getOwner();
        shopRepository.delete(shop);

        roleRepository.findByName("SELLER").ifPresent(sellerRole -> {
            owner.getRoles().remove(sellerRole);
            userRepository.save(owner);
        });

        return ResponseEntity.noContent().build();
    }
}
