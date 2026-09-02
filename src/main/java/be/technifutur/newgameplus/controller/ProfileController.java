package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.UpdateProfileRequest;
import be.technifutur.newgameplus.dto.response.MeResponse;
import be.technifutur.newgameplus.entities.User;
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
@RequiredArgsConstructor
public class ProfileController {

    private final UserRepository userRepository;

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
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        userRepository.deleteById(session.id());
        return ResponseEntity.noContent().build();
    }
}
