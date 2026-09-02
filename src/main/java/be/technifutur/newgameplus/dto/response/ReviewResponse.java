package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Review;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReviewResponse(
        UUID orderId,
        String auhtorUsername,
        @Min(1) @Max(5) int rating,
        String comment,
        LocalDateTime createdAt
) {
    public static ReviewResponse fromReview(Review review) {
        return new ReviewResponse(review.getId(), review.getAuthor().getUsername(), review.getRating(), review.getComment(), review.getCreatedAt());
    }
}
