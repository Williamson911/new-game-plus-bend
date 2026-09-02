package be.technifutur.newgameplus.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AddToCartRequest(@NotNull UUID listingId) {
}
