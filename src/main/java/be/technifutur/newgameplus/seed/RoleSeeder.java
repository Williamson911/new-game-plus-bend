package be.technifutur.newgameplus.seed;

import be.technifutur.newgameplus.entities.Role;
import be.technifutur.newgameplus.repositories.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RoleSeeder implements CommandLineRunner {

    private final RoleRepository roleRepository;

    @Override
    public void run(String... args) {
        if (roleRepository.count() == 0) {
            roleRepository.saveAll(List.of(
                    new Role("BUYER"),
                    new Role("SELLER"),
                    new Role("ADMIN")
            ));
        }
    }
}
