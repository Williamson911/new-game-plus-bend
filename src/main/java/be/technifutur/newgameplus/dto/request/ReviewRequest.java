package be.technifutur.newgameplus.dto.request;

import be.technifutur.newgameplus.entities.Review;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReviewRequest(
        @NotNull UUID orderId,
        @Min(1) @Max(5) int rating,
        String comment
) {
    public Review toReview() {
        Review review = new Review();
        review.setRating(rating);
        review.setComment(comment);
        return review;
    }
}
