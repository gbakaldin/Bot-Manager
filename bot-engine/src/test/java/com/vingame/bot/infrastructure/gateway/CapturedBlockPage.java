package com.vingame.bot.infrastructure.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Cloudflare block page this host actually received on 2026-09-17, headers and all
 * ({@code cloudflare/cf-block-raw.txt}, copied from
 * {@code docs/reviews/WIN79_119_PROD_ACCOUNTS/cf-block-raw.txt} with the egress IP in the footer
 * replaced by a TEST-NET address — the classifier never reads it).
 * <p>
 * Shared test fixture: the detector test classifies it, and {@code StubGateway}'s block mode
 * serves it, so "the page we test against" and "the page the stub answers with" cannot drift.
 */
public record CapturedBlockPage(int status, Map<String, String> headers, String body) {

    public static final String RESOURCE = "/cloudflare/cf-block-raw.txt";

    /** The {@code cf-ray} of the captured page — the value the ERROR line and the SA ticket carry. */
    public static final String CF_RAY = "a3c7e4004acc850e-HKG";

    /** Parse the resource: a {@code captured …} line, an {@code HTTP/2 403} line, headers, body. */
    public static CapturedBlockPage load() {
        String raw;
        try (InputStream in = CapturedBlockPage.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the test classpath");
            }
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String[] lines = raw.split("\n", -1);
        int i = 0;
        if (lines[i].startsWith("captured")) {
            i++;
        }
        String[] statusLine = lines[i++].trim().split("\\s+");
        int status = Integer.parseInt(statusLine[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (; i < lines.length; i++) {
            String line = lines[i].replace("\r", "");
            if (line.isEmpty()) {
                i++;
                break;
            }
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }
        StringBuilder body = new StringBuilder();
        for (; i < lines.length; i++) {
            body.append(lines[i]);
            if (i < lines.length - 1) {
                body.append('\n');
            }
        }
        return new CapturedBlockPage(status, Map.copyOf(headers), body.toString());
    }

    /** Case-insensitive lookup in the shape {@link CloudflareBlockDetector#classify(int, java.util.function.Function, String)} takes. */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }
}
