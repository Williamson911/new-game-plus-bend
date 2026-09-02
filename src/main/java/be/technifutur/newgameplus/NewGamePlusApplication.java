package be.technifutur.newgameplus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NewGamePlusApplication {

    public static void main(String[] args) {
        SpringApplication.run(NewGamePlusApplication.class, args);
    }

}
