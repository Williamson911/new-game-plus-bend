package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.entities.Game;
import be.technifutur.newgameplus.entities.Listing;
import be.technifutur.newgameplus.entities.Shop;
import be.technifutur.newgameplus.entities.User;
import be.technifutur.newgameplus.repositories.GameRepository;
import be.technifutur.newgameplus.repositories.ListingRepository;
import be.technifutur.newgameplus.repositories.ShopRepository;
import be.technifutur.newgameplus.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ListingDiscoveryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ListingRepository listingRepository;

    private Shop shop;

    @BeforeEach
    void setUp() {
        User owner = new User("shop-owner", "shop-owner@example.com", "irrelevant-hash");
        userRepository.save(owner);

        shop = new Shop();
        shop.setOwner(owner);
        shop.setName("Discovery Test Shop");
        shop.setDescription("Shop used by ListingDiscoveryControllerTest");
        shopRepository.save(shop);
    }

    private Game createGame(String uniqueSuffix) {
        Game game = new Game();
        game.setName("Test Game " + uniqueSuffix);
        game.setDescription("A game used for tests");
        game.setPublisher("Test Publisher");
        game.setDeveloper("Test Developer");
        game.setPlatform("PC");
        game.setReleaseDate(LocalDate.of(2020, 1, 1));
        game.setCoverURL("https://example.com/cover-" + uniqueSuffix + ".png");
        game.setIgdbID("igdb-" + uniqueSuffix);
        return gameRepository.save(game);
    }

    private Listing createListing(Game game, BigDecimal price, boolean featured) {
        Listing listing = new Listing();
        listing.setGame(game);
        listing.setShop(shop);
        listing.setPrice(price);
        listing.setFeatured(featured);
        return listingRepository.save(listing);
    }

    @Test
    void latestReturnsMostRecentListingsFirst() throws Exception {
        Listing older = createListing(createGame("latest-older"), new BigDecimal("20.00"), false);
        Thread.sleep(5);
        Listing newer = createListing(createGame("latest-newer"), new BigDecimal("20.00"), false);

        mockMvc.perform(get("/listings/latest").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(newer.getId().toString()))
                .andExpect(jsonPath("$[1].id").value(older.getId().toString()));
    }

    @Test
    void cheapReturnsOnlyListingsAtOrBelowThreshold() throws Exception {
        Listing cheap = createListing(createGame("cheap-under"), new BigDecimal("10.00"), false);
        createListing(createGame("cheap-over"), new BigDecimal("25.00"), false);

        mockMvc.perform(get("/listings/cheap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(cheap.getId().toString()))
                .andExpect(jsonPath("$[0].price").value(10.00));
    }

    @Test
    void featuredReturnsOnlyAvailableFeaturedListings() throws Exception {
        Listing featuredAvailable = createListing(createGame("featured-available"), new BigDecimal("30.00"), true);
        createListing(createGame("featured-not-flagged"), new BigDecimal("30.00"), false);

        Listing featuredSold = createListing(createGame("featured-sold"), new BigDecimal("30.00"), true);
        featuredSold.setStatus(be.technifutur.newgameplus.entities.ListingStatus.SOLD);
        listingRepository.save(featuredSold);

        mockMvc.perform(get("/listings/featured"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(featuredAvailable.getId().toString()));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "BUYER")
    void patchFeaturedIsForbiddenForNonAdmin() throws Exception {
        Listing listing = createListing(createGame("patch-forbidden"), new BigDecimal("30.00"), false);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/listings/{id}/featured", listing.getId())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"featured\": true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
    void patchFeaturedUpdatesFlagForAdmin() throws Exception {
        Listing listing = createListing(createGame("patch-allowed"), new BigDecimal("30.00"), false);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/listings/{id}/featured", listing.getId())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"featured\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featured").value(true));

        Listing reloaded = listingRepository.findById(listing.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(reloaded.isFeatured());
    }
}
