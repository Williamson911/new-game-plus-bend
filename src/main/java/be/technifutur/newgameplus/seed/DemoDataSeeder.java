package be.technifutur.newgameplus.seed;

import be.technifutur.newgameplus.entities.*;
import be.technifutur.newgameplus.igdb.IgdbClient;
import be.technifutur.newgameplus.igdb.IgdbGame;
import be.technifutur.newgameplus.repositories.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Order(2)
@Profile("!test")
public class DemoDataSeeder implements CommandLineRunner {

    private final GenreRepository genreRepository;
    private final GameRepository gameRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ShopRepository shopRepository;
    private final ListingRepository listingRepository;
    private final PasswordEncoder passwordEncoder;
    private final IgdbClient igdbClient;

    @Override
    public void run(String... args) {
        if (gameRepository.count() > 0) {
            return;
        }

        Genre rpg = genreRepository.save(new Genre("RPG"));
        Genre fps = genreRepository.save(new Genre("FPS"));
        Genre platformer = genreRepository.save(new Genre("Plateforme"));
        Genre adventure = genreRepository.save(new Genre("Aventure"));
        Genre strategy = genreRepository.save(new Genre("Stratégie"));

        Game zelda = createGame(
                "The Legend of Zelda: Breath of the Wild",
                "Exploration en monde ouvert dans le royaume d'Hyrule.",
                Set.of(adventure, rpg),
                "Nintendo", "Nintendo EPD", "Nintendo Switch",
                LocalDate.of(2017, 3, 3),
                "https://picsum.photos/seed/botw/400/600", "botw-001"
        );

        Game eldenRing = createGame(
                "Elden Ring",
                "RPG d'action dans les Terres Interdites, coécrit avec George R. R. Martin.",
                Set.of(rpg, adventure),
                "Bandai Namco", "FromSoftware", "PC",
                LocalDate.of(2022, 2, 25),
                "https://picsum.photos/seed/eldenring/400/600", "elden-ring-001"
        );

        Game haloInfinite = createGame(
                "Halo Infinite",
                "Retour du Master Chief dans un FPS en monde semi-ouvert.",
                Set.of(fps),
                "Xbox Game Studios", "343 Industries", "Xbox Series X",
                LocalDate.of(2021, 12, 8),
                "https://picsum.photos/seed/haloinfinite/400/600", "halo-infinite-001"
        );

        Game celeste = createGame(
                "Celeste",
                "Plateformer exigeant sur l'ascension d'une montagne, et de soi-même.",
                Set.of(platformer),
                "Maddy Makes Games", "Maddy Makes Games", "PC",
                LocalDate.of(2018, 1, 25),
                "https://picsum.photos/seed/celeste/400/600", "celeste-001"
        );

        Game civ6 = createGame(
                "Sid Meier's Civilization VI",
                "Bâtissez un empire qui traversera les âges.",
                Set.of(strategy),
                "2K Games", "Firaxis Games", "PC",
                LocalDate.of(2016, 10, 21),
                "https://picsum.photos/seed/civ6/400/600", "civ6-001"
        );

        Role buyerRole = roleRepository.findByName("BUYER").orElseThrow();
        Role sellerRole = roleRepository.findByName("SELLER").orElseThrow();
        Role adminRole = roleRepository.findByName("ADMIN").orElseThrow();

        User admin = new User("admin", "admin@newgameplus.local", passwordEncoder.encode("admin123"));
        admin.getRoles().add(adminRole);
        admin.setConfirmed(true);
        userRepository.save(admin);

        User buyer = new User("buyer1", "buyer1@newgameplus.local", passwordEncoder.encode("password123"));
        buyer.getRoles().add(buyerRole);
        buyer.setConfirmed(true);
        userRepository.save(buyer);

        User seller = new User("seller1", "seller1@newgameplus.local", passwordEncoder.encode("password123"));
        seller.getRoles().add(buyerRole);
        seller.getRoles().add(sellerRole);
        seller.setConfirmed(true);
        userRepository.save(seller);

        Shop shop = new Shop();
        shop.setOwner(seller);
        shop.setName("Seller1 Games");
        shop.setDescription("Boutique de test remplie de classiques d'occasion.");
        shopRepository.save(shop);

        createListing(shop, zelda, new BigDecimal("39.90"));
        Listing eldenRingListing = createListing(shop, eldenRing, new BigDecimal("49.90"));
        eldenRingListing.setFeatured(true);
        listingRepository.save(eldenRingListing);
        createListing(shop, haloInfinite, new BigDecimal("19.90"));
        createListing(shop, celeste, new BigDecimal("14.90"));
        createListing(shop, civ6, new BigDecimal("24.90"));
    }

    private Game createGame(String name, String fallbackDescription, Set<Genre> genres,
                             String publisher, String developer, String platform,
                             LocalDate fallbackReleaseDate, String fallbackCoverURL, String fallbackIgdbID) {
        Optional<IgdbGame> igdbGame = igdbClient.searchGame(name);

        Game game = new Game();
        game.setName(name);
        game.setGenres(new HashSet<>(genres));
        game.setPublisher(publisher);
        game.setDeveloper(developer);
        game.setPlatform(platform);
        String description = igdbGame.map(IgdbGame::description).orElse(fallbackDescription);
        game.setDescription(description.length() > 2000 ? description.substring(0, 2000) : description);
        game.setReleaseDate(igdbGame.map(IgdbGame::releaseDate).orElse(fallbackReleaseDate));
        game.setCoverURL(igdbGame.map(IgdbGame::coverURL).orElse(fallbackCoverURL));
        game.setIgdbID(igdbGame.map(IgdbGame::igdbId).orElse(fallbackIgdbID));
        return gameRepository.save(game);
    }

    private Listing createListing(Shop shop, Game game, BigDecimal price) {
        Listing listing = new Listing();
        listing.setShop(shop);
        listing.setGame(game);
        listing.setPrice(price);
        return listingRepository.save(listing);
    }
}
