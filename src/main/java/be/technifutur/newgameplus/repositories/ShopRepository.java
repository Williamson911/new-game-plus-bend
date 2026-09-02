package be.technifutur.newgameplus.repositories;

import be.technifutur.newgameplus.entities.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShopRepository extends JpaRepository<Shop, UUID> {

    Optional<Shop> findByOwnerId(UUID ownerId);

    boolean existsByOwnerId(UUID ownerId);

    boolean existsByName(String name);
}
