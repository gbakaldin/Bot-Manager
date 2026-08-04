package com.vingame.bot.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Service for managing display names loaded from a resource file.
 * Names are loaded once at startup and served randomly.
 */
@Slf4j
@Service
public class DisplayNameService {

    private static final String DISPLAY_NAMES_FILE = "display_names.txt";

    private final List<String> displayNames = new ArrayList<>();

    @PostConstruct
    public void init() {
        loadDisplayNames();
    }

    private void loadDisplayNames() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(DISPLAY_NAMES_FILE)) {
            if (is == null) {
                log.warn("Display names file not found: {}. Display name feature will be disabled.", DISPLAY_NAMES_FILE);
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        displayNames.add(trimmed);
                    }
                }
            }

            log.info("Loaded {} display names from {}", displayNames.size(), DISPLAY_NAMES_FILE);
        } catch (IOException e) {
            log.error("Failed to load display names from {}", DISPLAY_NAMES_FILE, e);
        }
    }

    /**
     * Get a random display name from the loaded list.
     *
     * @return A random display name, or null if no names are available
     */
    public String getRandomDisplayName() {
        if (displayNames.isEmpty()) {
            return null;
        }
        int index = ThreadLocalRandom.current().nextInt(displayNames.size());
        return displayNames.get(index);
    }

    /**
     * Check if display names are available.
     *
     * @return true if names were loaded successfully
     */
    public boolean hasDisplayNames() {
        return !displayNames.isEmpty();
    }

    /**
     * Get the total number of loaded display names.
     *
     * @return count of available names
     */
    public int getDisplayNameCount() {
        return displayNames.size();
    }
}
