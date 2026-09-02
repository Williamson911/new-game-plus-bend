package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.LoginRequest;
import be.technifutur.newgameplus.dto.request.RegisterRequest;
import be.technifutur.newgameplus.dto.response.AuthResponse;
import be.technifutur.newgameplus.dto.response.RegisterResponse;
import be.technifutur.newgameplus.entities.Role;
import be.technifutur.newgameplus.entities.User;
import be.technifutur.newgameplus.repositories.RoleRepository;
import be.technifutur.newgameplus.repositories.UserRepository;
import be.technifutur.newgameplus.security.JwtUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nom d'utilisateur déjà pris");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email déjà utilisé");
        }

        Role buyerRole = roleRepository.findByName("BUYER")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Rôle BUYER introuvable"));

        User user = request.toUser();
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.getRoles().add(buyerRole);
        userRepository.save(user);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterResponse(user.getId(), user.getUsername()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identifiants invalides"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identifiants invalides");
        }

        return ResponseEntity.ok(new AuthResponse(jwtUtils.generateToken(user)));
    }
}
