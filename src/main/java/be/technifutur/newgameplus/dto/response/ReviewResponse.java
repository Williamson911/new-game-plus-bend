package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Review;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReviewResponse(
        UUID id,
        String authorUsername,
        int rating,
        String comment,
        LocalDateTime createdAt
) {
    public static ReviewResponse fromReview(Review review) {
        return new ReviewResponse(
                review.getId(),
                review.getAuthor().getUsername(),
                review.getRating(),
                review.getComment(),
                review.getCreatedAt()
        );
    }
}
