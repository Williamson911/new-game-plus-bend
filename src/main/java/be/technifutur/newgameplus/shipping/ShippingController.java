package be.technifutur.newgameplus.shipping;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/shipping")
@RequiredArgsConstructor
@Tag(name = "Shipping", description = "Recherche de points relais Mondial Relay")
public class ShippingController {

    private final MondialRelayClient mondialRelayClient;

    @GetMapping("/relay-points")
    public ResponseEntity<List<MondialRelayClient.RelayPointResult>> findRelayPoints(
            @RequestParam String postCode,
            @RequestParam(defaultValue = "BE") String country
    ) {
        return ResponseEntity.ok(mondialRelayClient.search(postCode, country));
    }
}
