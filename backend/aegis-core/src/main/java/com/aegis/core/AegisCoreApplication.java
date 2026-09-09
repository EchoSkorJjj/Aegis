package com.aegis.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Aegis Core — the domain and pipeline service: inventory &amp; map, ingestion, matching, scoring,
 * and actions. The AI steps (assess + draft) live in a separate Python ai-service, called through
 * the {@code ai} port. Runs as a modular monolith for the MVP; see {@code ARCHITECTURE.md}.
 */
@SpringBootApplication
@EnableScheduling // ingestion polls advisory feeds on a schedule
public class AegisCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(AegisCoreApplication.class, args);
    }
}
