package be.technifutur.newgameplus.repositories;

import be.technifutur.newgameplus.entities.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    List<Review> findByShopId(UUID shopId);

    boolean existsByOrderId(UUID orderId);

    boolean existsByAuthorId(UUID authorId);

    @Query("select avg(r.rating) from Review r where r.shop.id = :shopId")
    Double findAverageRatingByShopId(@Param("shopId") UUID shopId);
}
