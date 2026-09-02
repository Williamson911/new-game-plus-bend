package be.technifutur.newgameplus.repositories;

import be.technifutur.newgameplus.entities.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByCartId(UUID cartId);

    Optional<CartItem> findByCartIdAndListingId(UUID cartId, UUID listingId);

    void deleteByCartId(UUID cartId);

    void deleteByListingId(UUID listingId);
}
