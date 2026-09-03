package be.technifutur.newgameplus.shipping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShippingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MondialRelayClient mondialRelayClient;

    @Test
    void findRelayPointsReturnsClientResults() throws Exception {
        when(mondialRelayClient.search(anyString(), anyString())).thenReturn(List.of(
                new MondialRelayClient.RelayPointResult("047368", "Superette du Coin", "Rue de la Gare 12", "4000", "Liège", "BE")
        ));

        mockMvc.perform(get("/shipping/relay-points").param("postCode", "4000").param("country", "BE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("047368"))
                .andExpect(jsonPath("$[0].city").value("Liège"));
    }

    @Test
    void findRelayPointsDefaultsCountryToBelgium() throws Exception {
        when(mondialRelayClient.search("1000", "BE")).thenReturn(List.of());

        mockMvc.perform(get("/shipping/relay-points").param("postCode", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
