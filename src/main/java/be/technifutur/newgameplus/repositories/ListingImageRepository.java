package be.technifutur.newgameplus.repositories;

import be.technifutur.newgameplus.entities.ListingImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ListingImageRepository extends JpaRepository<ListingImage, UUID> {

    List<ListingImage> findByListingId(UUID listingId);

    void deleteByListingId(UUID listingId);
}
