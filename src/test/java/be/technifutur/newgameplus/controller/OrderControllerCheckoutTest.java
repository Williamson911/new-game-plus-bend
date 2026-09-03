package be.technifutur.newgameplus.controller;

import be.technifutur.newgameplus.entities.*;
import be.technifutur.newgameplus.payment.StripeClient;
import be.technifutur.newgameplus.repositories.*;
import be.technifutur.newgameplus.security.JwtUtils;
import com.stripe.exception.StripeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderControllerCheckoutTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoBean
    private StripeClient stripeClient;

    private String buyerToken;
    private Cart cart;

    @BeforeEach
    void setUp() throws StripeException {
        Role buyerRole = roleRepository.findByName("BUYER").orElseGet(() -> roleRepository.save(new Role("BUYER")));
        Role sellerRole = roleRepository.findByName("SELLER").orElseGet(() -> roleRepository.save(new Role("SELLER")));

        User buyer = new User("checkout-buyer", "checkout-buyer@example.com", passwordEncoder.encode("password123"));
        buyer.getRoles().add(buyerRole);
        buyer.setConfirmed(true);
        userRepository.save(buyer);
        buyerToken = jwtUtils.generateToken(buyer);

        cart = new Cart();
        cart.setBuyer(buyer);
        cartRepository.save(cart);

        when(stripeClient.createCheckoutSession(any(), any()))
                .thenReturn(new StripeClient.CheckoutSession("cs_test_123", "https://checkout.stripe.com/test"));
    }

    private Shop createShop(String suffix) {
        Role sellerRole = roleRepository.findByName("SELLER").orElseThrow();
        User seller = new User("seller-" + suffix, "seller-" + suffix + "@example.com", passwordEncoder.encode("password123"));
        seller.getRoles().add(sellerRole);
        seller.setConfirmed(true);
        userRepository.save(seller);

        Shop shop = new Shop();
        shop.setOwner(seller);
        shop.setName("Checkout Shop " + suffix);
        shop.setDescription("Shop used by OrderControllerCheckoutTest");
        return shopRepository.save(shop);
    }

    private Listing createListing(Shop shop, int weightGrams, BigDecimal price, String suffix) {
        Game game = new Game();
        game.setName("Checkout Test Game " + suffix);
        game.setDescription("A game used for checkout tests");
        game.setPublisher("Test Publisher");
        game.setDeveloper("Test Developer");
        game.setPlatform("PC");
        game.setReleaseDate(LocalDate.of(2020, 1, 1));
        game.setCoverURL("https://example.com/checkout-cover-" + suffix + ".png");
        game.setIgdbID("checkout-igdb-" + suffix);
        game.setWeightGrams(weightGrams);
        gameRepository.save(game);

        Listing listing = new Listing();
        listing.setGame(game);
        listing.setShop(shop);
        listing.setPrice(price);
        return listingRepository.save(listing);
    }

    private void addToCart(Listing listing) {
        CartItem cartItem = new CartItem();
        cartItem.setCart(cart);
        cartItem.setListing(listing);
        cartItemRepository.save(cartItem);
    }

    @Test
    void homeCheckoutComputesShippingCostFromWeightAndUsesHomeRate() throws Exception {
        Shop shop = createShop("home");
        addToCart(createListing(shop, 800, new BigDecimal("20.00"), "home-800g"));

        String body = """
                {
                  "deliveryMode": "HOME",
                  "street": "Rue de la Paix",
                  "streetNumber": "12",
                  "postCode": "4000",
                  "city": "Liège",
                  "country": "BE"
                }
                """;

        mockMvc.perform(post("/orders/checkout")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.orders[0].deliveryMode").value("HOME"))
                .andExpect(jsonPath("$.orders[0].shippingCost").value(6.99))
                .andExpect(jsonPath("$.orders[0].shippingAddress.city").value("Liège"))
                .andExpect(jsonPath("$.orders[0].relayPoint").value(org.hamcrest.Matchers.nullValue()));

        Order order = orderRepository.findByShopId(shop.getId()).get(0);
        assertEquals(0, new BigDecimal("6.99").compareTo(order.getShippingCost()));
        assertNull(order.getRelayPoint());
    }

    @Test
    void relayPointCheckoutStoresSnapshotAndUsesRelayRate() throws Exception {
        Shop shop = createShop("relay");
        addToCart(createListing(shop, 2_000, new BigDecimal("30.00"), "relay-2000g"));

        String body = """
                {
                  "deliveryMode": "RELAY_POINT",
                  "relayPointId": "047368",
                  "relayPointName": "Superette du Coin",
                  "relayPointStreet": "Rue de la Gare 12",
                  "relayPointPostCode": "4000",
                  "relayPointCity": "Liège",
                  "relayPointCountry": "BE"
                }
                """;

        mockMvc.perform(post("/orders/checkout")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders[0].deliveryMode").value("RELAY_POINT"))
                .andExpect(jsonPath("$.orders[0].shippingCost").value(6.99))
                .andExpect(jsonPath("$.orders[0].relayPoint.relayId").value("047368"))
                .andExpect(jsonPath("$.orders[0].shippingAddress").value(org.hamcrest.Matchers.nullValue()));

        Order order = orderRepository.findByShopId(shop.getId()).get(0);
        assertEquals(0, new BigDecimal("6.99").compareTo(order.getShippingCost()));
        assertNull(order.getShippingAddress());
    }

    @Test
    void multiShopCartComputesShippingCostIndependentlyPerShop() throws Exception {
        Shop shopA = createShop("multi-a");
        Shop shopB = createShop("multi-b");
        addToCart(createListing(shopA, 200, new BigDecimal("10.00"), "multi-a-200g"));
        addToCart(createListing(shopB, 4_000, new BigDecimal("15.00"), "multi-b-4000g"));

        String body = """
                {
                  "deliveryMode": "HOME",
                  "street": "Rue de la Paix",
                  "streetNumber": "12",
                  "postCode": "4000",
                  "city": "Liège",
                  "country": "BE"
                }
                """;

        mockMvc.perform(post("/orders/checkout")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders", org.hamcrest.Matchers.hasSize(2)));

        Order orderA = orderRepository.findByShopId(shopA.getId()).get(0);
        Order orderB = orderRepository.findByShopId(shopB.getId()).get(0);
        assertEquals(0, new BigDecimal("4.99").compareTo(orderA.getShippingCost()));
        assertEquals(0, new BigDecimal("8.99").compareTo(orderB.getShippingCost()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Order>> ordersCaptor = ArgumentCaptor.forClass(List.class);
        verify(stripeClient).createCheckoutSession(any(), ordersCaptor.capture());
        assertEquals(2, ordersCaptor.getValue().size());
    }

    @Test
    void homeCheckoutWithBlankStreetIsRejected() throws Exception {
        Shop shop = createShop("invalid-home");
        addToCart(createListing(shop, 800, new BigDecimal("20.00"), "invalid-home-800g"));

        String body = """
                {
                  "deliveryMode": "HOME",
                  "street": "",
                  "streetNumber": "12",
                  "postCode": "4000",
                  "city": "Liège",
                  "country": "BE"
                }
                """;

        mockMvc.perform(post("/orders/checkout")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void relayPointCheckoutWithBlankRelayIdIsRejected() throws Exception {
        Shop shop = createShop("invalid-relay");
        addToCart(createListing(shop, 800, new BigDecimal("20.00"), "invalid-relay-800g"));

        String body = """
                {
                  "deliveryMode": "RELAY_POINT",
                  "relayPointId": "",
                  "relayPointName": "Superette du Coin",
                  "relayPointStreet": "Rue de la Gare 12",
                  "relayPointPostCode": "4000",
                  "relayPointCity": "Liège",
                  "relayPointCountry": "BE"
                }
                """;

        mockMvc.perform(post("/orders/checkout")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
