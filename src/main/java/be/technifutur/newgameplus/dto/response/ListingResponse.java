package be.technifutur.newgameplus.dto.response;

import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.ListingStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ListingResponse(
        UUID id,
        String gameName,
        String shopName,
        BigDecimal price,
        ListingStatus status,
        boolean featured,
        List<String> imageUrls
) {
    public static ListingResponse fromListing(Listing listing, List<String> imageUrls) {
        return new ListingResponse(
                listing.getId(),
                listing.getGame().getName(),
                listing.getShop().getName(),
                listing.getPrice(),
                listing.getStatus(),
                listing.isFeatured(),
                imageUrls
        );
    }
}
