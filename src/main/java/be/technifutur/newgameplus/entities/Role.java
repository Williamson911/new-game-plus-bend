package be.technifutur.newgameplus.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(name = "role_")
@NoArgsConstructor @AllArgsConstructor
@ToString(of = {"name"}) @EqualsAndHashCode(of = {"name"})
public class Role {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Getter
    private UUID id;

    @Column(unique = true, nullable = false, length = 50)
    @Getter @Setter
    private String name;

    public Role(String name) {
        this.name = name;
    }
}
