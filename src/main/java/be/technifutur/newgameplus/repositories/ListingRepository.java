package be.technifutur.newgameplus.repositories;

import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.ListingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ListingRepository extends JpaRepository<Listing, UUID>,
        JpaSpecificationExecutor<Listing> {

    List<Listing> findByStatus(ListingStatus status);

    List<Listing> findBySellerId(UUID sellerId);

    List<Listing> findByGameId(UUID gameId);
}
