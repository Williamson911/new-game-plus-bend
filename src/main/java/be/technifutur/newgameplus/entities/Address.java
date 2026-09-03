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
public class Address {

    private String city;

    private String street;

    private String streetNumber;

    private String postCode;

    private String country;

}
