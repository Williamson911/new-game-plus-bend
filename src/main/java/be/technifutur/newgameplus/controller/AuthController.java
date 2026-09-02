package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.ForgotPasswordRequest;
import be.technifutur.newgameplus.dto.request.LoginRequest;
import be.technifutur.newgameplus.dto.request.RegisterRequest;
import be.technifutur.newgameplus.dto.request.ResendConfirmationRequest;
import be.technifutur.newgameplus.dto.request.ResetPasswordRequest;
import be.technifutur.newgameplus.dto.response.AuthResponse;
import be.technifutur.newgameplus.dto.response.ConfirmResponse;
import be.technifutur.newgameplus.dto.response.RegisterResponse;
import be.technifutur.newgameplus.entities.Role;
import be.technifutur.newgameplus.entities.User;
import be.technifutur.newgameplus.mailer.MailerUtils;
import be.technifutur.newgameplus.repositories.RoleRepository;
import be.technifutur.newgameplus.repositories.UserRepository;
import be.technifutur.newgameplus.security.JwtUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.context.Context;

import java.util.UUID;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Inscription, connexion et gestion du mot de passe")
public class AuthController {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final MailerUtils mailerUtils;

    @Value("${app.frontend-url}")
    private String frontendUrl;

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
        user.setConfirmed(false);
        user.setConfirmationToken(UUID.randomUUID().toString());
        userRepository.save(user);

        sendConfirmationEmail(user);

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
        if (!user.isConfirmed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Confirme ton email avant de te connecter");
        }

        return ResponseEntity.ok(new AuthResponse(jwtUtils.generateToken(user)));
    }

    @GetMapping("/confirm")
    public ResponseEntity<ConfirmResponse> confirm(@RequestParam String token) {
        User user = userRepository.findByConfirmationToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token de confirmation invalide ou expiré"));

        user.setConfirmed(true);
        user.setConfirmationToken(null);
        userRepository.save(user);

        return ResponseEntity.ok(new ConfirmResponse(user.getUsername()));
    }

    @PostMapping("/resend-confirmation")
    public ResponseEntity<Void> resendConfirmation(
            @Valid @RequestBody ResendConfirmationRequest request
    ) {
        userRepository.findByEmail(request.email()).ifPresent(user -> {
            if (user.isConfirmed()) {
                return;
            }
            user.setConfirmationToken(UUID.randomUUID().toString());
            userRepository.save(user);
            sendConfirmationEmail(user);
        });

        return ResponseEntity.ok().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request
    ) {
        userRepository.findByEmail(request.email()).ifPresent(user -> {
            user.setPasswordResetToken(UUID.randomUUID().toString());
            userRepository.save(user);

            Context context = new Context();
            context.setVariable("username", user.getUsername());
            context.setVariable("resetLink", frontendUrl + "/auth/reset?token=" + user.getPasswordResetToken());

            new Thread(mailerUtils.createThread(
                    "Réinitialise ton mot de passe New Game Plus",
                    "reset-password",
                    context,
                    user.getEmail()
            )).start();
        });
        // Renvoie toujours 200, que l'email existe ou non, pour ne pas permettre l'énumération de comptes.
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        User user = userRepository.findByPasswordResetToken(request.token())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token de réinitialisation invalide ou expiré"));

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setPasswordResetToken(null);
        userRepository.save(user);

        return ResponseEntity.ok().build();
    }

    private void sendConfirmationEmail(User user) {
        Context context = new Context();
        context.setVariable("username", user.getUsername());
        context.setVariable("confirmLink", frontendUrl + "/auth/verified?token=" + user.getConfirmationToken());

        new Thread(mailerUtils.createThread(
                "Confirme ton compte New Game Plus",
                "confirm-account",
                context,
                user.getEmail()
        )).start();
    }
}
