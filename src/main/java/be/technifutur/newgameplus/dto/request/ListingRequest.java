package be.technifutur.newgameplus.dto.request;

import be.technifutur.newgameplus.entities.Genre;
import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.ListingStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record ListingRequest(
        @NotNull UUID gameId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal price
) {
    public Listing toListing() {
        Listing listing = new Listing();
        listing.setPrice(price);
        return listing;
    }
}
