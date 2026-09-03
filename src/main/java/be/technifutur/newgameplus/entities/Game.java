package be.technifutur.newgameplus.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id"})
@EqualsAndHashCode(of = {"id"})
@Getter
@Setter
public class Game {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "game_genre",
            joinColumns = @JoinColumn(name = "game_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id")
    )
    private Set<Genre> genres = new HashSet<>();

    @Column(nullable = false, length = 50)
    private String publisher;

    @Column(nullable = false, length = 50)
    private String developer;

    @Column(nullable = false, length = 50)
    private String platform;

    @Column(nullable = false)
    private LocalDate releaseDate;

    @Column(nullable = false, unique = true, length = 500)
    private String coverURL;

    @Column(nullable = false, unique = true, length = 50)
    private String igdbID;

    @Column(nullable = false, columnDefinition = "integer not null default 200")
    private int weightGrams;

}
