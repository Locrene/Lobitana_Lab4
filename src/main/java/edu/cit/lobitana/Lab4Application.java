package edu.cit.lobitana;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Lab 4 entry point.
 *
 * The application is the only process that talks to Tiangge and LegacySupply. Everything it does is
 * driven from inside: schedulers discover work (feed polling, delivery tracking, heartbeats) and
 * domain events push stock changes outward.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class Lab4Application {

    public static void main(String[] args) {
        SpringApplication.run(Lab4Application.class, args);
    }
}
