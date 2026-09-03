package be.technifutur.newgameplus.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@Getter
@Setter
public class RelayPoint {

    @Column(name = "relay_id")
    private String relayId;

    @Column(name = "relay_name")
    private String relayName;

    @Column(name = "relay_street")
    private String relayStreet;

    @Column(name = "relay_post_code")
    private String relayPostCode;

    @Column(name = "relay_city")
    private String relayCity;

    @Column(name = "relay_country")
    private String relayCountry;

}
