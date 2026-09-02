package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.ShopRequest;
import be.technifutur.newgameplus.dto.response.ShopResponse;
import be.technifutur.newgameplus.entities.Role;
import be.technifutur.newgameplus.entities.Shop;
import be.technifutur.newgameplus.entities.User;
import be.technifutur.newgameplus.repositories.RoleRepository;
import be.technifutur.newgameplus.repositories.ShopRepository;
import be.technifutur.newgameplus.repositories.UserRepository;
import be.technifutur.newgameplus.security.JwtUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/shops")
@RequiredArgsConstructor
public class ShopController {

    private final ShopRepository shopRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

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
}
