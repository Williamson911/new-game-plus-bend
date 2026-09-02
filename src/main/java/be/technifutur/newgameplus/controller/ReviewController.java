package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.dto.request.ReviewRequest;
import be.technifutur.newgameplus.dto.response.ReviewResponse;
import be.technifutur.newgameplus.entities.Order;
import be.technifutur.newgameplus.entities.Review;
import be.technifutur.newgameplus.entities.User;
import be.technifutur.newgameplus.repositories.OrderRepository;
import be.technifutur.newgameplus.repositories.ReviewRepository;
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
@RequestMapping("/reviews")
@RequiredArgsConstructor
@Tag(name = "Reviews", description = "Avis laissés par les buyers sur les boutiques")
public class ReviewController {

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    @GetMapping("/shop/{shopId}")
    public ResponseEntity<List<ReviewResponse>> findByShop(@PathVariable UUID shopId) {
        List<ReviewResponse> reviews = reviewRepository.findByShopId(shopId).stream()
                .map(ReviewResponse::fromReview)
                .toList();
        return ResponseEntity.ok(reviews);
    }

    @PostMapping
    public ResponseEntity<ReviewResponse> create(
            @Valid @RequestBody ReviewRequest request,
            @AuthenticationPrincipal JwtUtils.UserSession session
    ) {
        Order order = orderRepository.findById(request.orderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Commande introuvable"));

        if (!order.getBuyer().getId().equals(session.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cette commande ne t'appartient pas");
        }
        if (reviewRepository.existsByOrderId(order.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà laissé un avis pour cette commande");
        }

        User author = userRepository.findById(session.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));

        Review review = request.toReview();
        review.setAuthor(author);
        review.setShop(order.getShop());
        review.setOrder(order);
        review = reviewRepository.saveAndFlush(review);

        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewResponse.fromReview(review));
    }
}
