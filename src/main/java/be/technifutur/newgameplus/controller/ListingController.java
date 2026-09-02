package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.ListingRequest;
import be.technifutur.newgameplus.dto.response.ListingResponse;
import be.technifutur.newgameplus.entities.Game;
import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.ListingStatus;
import be.technifutur.newgameplus.entities.ListingImage;
import be.technifutur.newgameplus.entities.Shop;
import be.technifutur.newgameplus.repositories.CartItemRepository;
import be.technifutur.newgameplus.repositories.GameRepository;
import be.technifutur.newgameplus.repositories.ListingImageRepository;
import be.technifutur.newgameplus.repositories.ListingRepository;
import be.technifutur.newgameplus.repositories.ShopRepository;
import be.technifutur.newgameplus.security.JwtUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/listings")
@RequiredArgsConstructor
@Tag(name = "Listings", description = "Annonces de jeux d'occasion")
public class ListingController {

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

    @GetMapping("/{id}")
    public ResponseEntity<ListingResponse> findById(@PathVariable UUID id) {
        Listing listing = listingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing introuvable"));
        return ResponseEntity.ok(toResponse(listing));
    }

    @PreAuthorize("hasRole('SELLER')")
    @PostMapping
    public ResponseEntity<ListingResponse> create(
            @Valid @RequestBody ListingRequest request,
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        Shop shop = shopRepository.findByOwnerId(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tu n'as pas encore de boutique"));

        Game game = gameRepository.findById(request.gameId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Game introuvable"));

        Listing listing = request.toListing();
        listing.setGame(game);
        listing.setShop(shop);
        listingRepository.save(listing);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(listing));
    }

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

    @PreAuthorize("hasRole('SELLER')")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        Listing listing = listingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing introuvable"));

        assertOwnedBySession(listing, session);

        if (listing.getStatus() == ListingStatus.SOLD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Impossible de supprimer une annonce déjà vendue");
        }

        listingImageRepository.deleteByListingId(listing.getId());
        cartItemRepository.deleteByListingId(listing.getId());
        listingRepository.delete(listing);
        return ResponseEntity.noContent().build();
    }

    private void assertOwnedBySession(Listing listing, JwtUtils.UserSession session) {
        if (!listing.getShop().getOwner().getId().equals(session.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cette annonce ne t'appartient pas");
        }
    }

    private ListingResponse toResponse(Listing listing) {
        List<String> imageUrls = listingImageRepository.findByListingId(listing.getId())
                .stream()
                .map(ListingImage::getUrl)
                .toList();
        return ListingResponse.fromListing(listing, imageUrls);
    }
}
