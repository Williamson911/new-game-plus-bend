package be.technifutur.newgameplus.repositories;

import be.technifutur.newgameplus.entities.Game;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface GameRepository extends JpaRepository<Game, UUID>,
        JpaSpecificationExecutor<Game> {

    Page<Game> findByNameContainingIgnoreCase(String name, Pageable pageable);

    @Query("select count(g) > 0 from Game g where g.name ilike :name")
    boolean existsByName(@Param("name") String name);

    @Query("select count(g) > 0 from Game g where g.name ilike :name and g.id <> :id")
    boolean existsByNameAndIdNot(@Param("name") String name, @Param("id") UUID id);
}
