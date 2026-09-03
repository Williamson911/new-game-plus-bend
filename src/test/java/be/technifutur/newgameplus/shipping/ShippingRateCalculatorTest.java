package be.technifutur.newgameplus.shipping;

import be.technifutur.newgameplus.entities.DeliveryMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShippingRateCalculatorTest {

    private final ShippingRateCalculator calculator = new ShippingRateCalculator();

    @Test
    void relayPointExactlyAtFirstTierBoundary() {
        assertEquals(0, new BigDecimal("4.15").compareTo(
                calculator.calculate(DeliveryMode.RELAY_POINT, 250)));
    }

    @Test
    void relayPointJustOverFirstTierBoundary() {
        assertEquals(0, new BigDecimal("5.99").compareTo(
                calculator.calculate(DeliveryMode.RELAY_POINT, 251)));
    }

    @Test
    void homeExactlyAtFirstTierBoundary() {
        assertEquals(0, new BigDecimal("4.99").compareTo(
                calculator.calculate(DeliveryMode.HOME, 250)));
    }

    @Test
    void homeJustOverFirstTierBoundary() {
        assertEquals(0, new BigDecimal("6.99").compareTo(
                calculator.calculate(DeliveryMode.HOME, 251)));
    }

    @Test
    void relayPointMidRangeWeight() {
        assertEquals(0, new BigDecimal("7.99").compareTo(
                calculator.calculate(DeliveryMode.RELAY_POINT, 4_000)));
    }

    @Test
    void weightAboveHighestTierFallsBackToLastTier() {
        assertEquals(0, new BigDecimal("13.49").compareTo(
                calculator.calculate(DeliveryMode.RELAY_POINT, 50_000)));
        assertEquals(0, new BigDecimal("17.99").compareTo(
                calculator.calculate(DeliveryMode.HOME, 50_000)));
    }

    @Test
    void zeroWeightUsesFirstTier() {
        assertEquals(0, new BigDecimal("4.15").compareTo(
                calculator.calculate(DeliveryMode.RELAY_POINT, 0)));
    }
}
