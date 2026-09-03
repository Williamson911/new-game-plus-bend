package be.technifutur.newgameplus.shipping;

import be.technifutur.newgameplus.entities.DeliveryMode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class ShippingRateCalculator {

    private record Tier(int maxGrams, BigDecimal relayPrice, BigDecimal homePrice) {
    }

    private static final List<Tier> TIERS = List.of(
            new Tier(250, new BigDecimal("4.15"), new BigDecimal("4.99")),
            new Tier(1_000, new BigDecimal("5.99"), new BigDecimal("6.99")),
            new Tier(3_000, new BigDecimal("6.99"), new BigDecimal("7.99")),
            new Tier(5_000, new BigDecimal("7.99"), new BigDecimal("8.99")),
            new Tier(10_000, new BigDecimal("9.49"), new BigDecimal("11.99")),
            new Tier(15_000, new BigDecimal("11.49"), new BigDecimal("14.99")),
            new Tier(20_000, new BigDecimal("13.49"), new BigDecimal("17.99"))
    );

    public BigDecimal calculate(DeliveryMode mode, int totalWeightGrams) {
        Tier tier = TIERS.stream()
                .filter(t -> totalWeightGrams <= t.maxGrams())
                .findFirst()
                .orElse(TIERS.get(TIERS.size() - 1));

        return mode == DeliveryMode.RELAY_POINT ? tier.relayPrice() : tier.homePrice();
    }
}
