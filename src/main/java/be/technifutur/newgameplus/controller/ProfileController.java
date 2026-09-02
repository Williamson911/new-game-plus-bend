package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.UpdateProfileRequest;
import be.technifutur.newgameplus.dto.response.MeResponse;
import be.technifutur.newgameplus.entities.User;
import be.technifutur.newgameplus.repositories.CartItemRepository;
import be.technifutur.newgameplus.repositories.CartRepository;
import be.technifutur.newgameplus.repositories.OrderRepository;
import be.technifutur.newgameplus.repositories.ReviewRepository;
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

@RestController
@RequiredArgsConstructor
@Tag(name = "Profile", description = "Profil de l'utilisateur connecté")
public class ProfileController {

    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        User user = userRepository.findById(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
        return ResponseEntity.ok(MeResponse.fromUser(user));
    }

    @PutMapping("/profile")
    public ResponseEntity<MeResponse> update(
            @AuthenticationPrincipal JwtUtils.UserSession session,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        if (userRepository.existsByUsernameAndIdNot(request.username(), session.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nom d'utilisateur déjà pris");
        }
        if (userRepository.existsByEmailAndIdNot(request.email(), session.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email déjà utilisé");
        }

        User user = userRepository.findById(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
        user.setUsername(request.username());
        user.setEmail(request.email());
        userRepository.save(user);

        return ResponseEntity.ok(MeResponse.fromUser(user));
    }

    @DeleteMapping("/account")
    @Transactional
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        if (shopRepository.existsByOwnerId(session.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supprime d'abord ta boutique avant de supprimer ton compte");
        }
        if (!orderRepository.findByBuyerId(session.id()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as des commandes associées à ton compte, suppression impossible");
        }
        if (reviewRepository.existsByAuthorId(session.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as laissé des avis associés à ton compte, suppression impossible");
        }

        cartRepository.findByBuyerId(session.id()).ifPresent(cart -> {
            cartItemRepository.deleteByCartId(cart.getId());
            cartRepository.delete(cart);
        });

        userRepository.deleteById(session.id());
        return ResponseEntity.noContent().build();
    }
}
