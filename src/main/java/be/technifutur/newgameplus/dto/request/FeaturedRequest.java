package be.technifutur.newgameplus.dto.request;

import jakarta.validation.constraints.NotNull;

public record FeaturedRequest(
        @NotNull Boolean featured
) {
}
