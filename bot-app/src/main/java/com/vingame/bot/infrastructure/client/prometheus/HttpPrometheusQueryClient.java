package com.vingame.bot.infrastructure.client.prometheus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.exception.UpstreamPrometheusException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PrometheusQueryClient} implementation over {@link java.net.http.HttpClient}
 * — the established HTTP idiom in this codebase (see {@code ApiGatewayClient}),
 * deliberately not WebClient/RestClient (neither is on the classpath).
 * <p>
 * Handles the full Prometheus response envelope (METRICS_API AD-7): non-2xx or
 * {@code status != "success"} → {@link UpstreamPrometheusException} (→ 502);
 * {@code NaN}/{@code +Inf}/{@code -Inf} sample values → {@code null}. Short
 * connect/read timeouts keep a slow Prometheus from stalling the public
 * endpoint thread.
 * <p>
 * Logging (CLAUDE.md): this is request-scoped infrastructure, not per-bot
 * lifecycle — successful queries DEBUG, failures WARN with the failing PromQL;
 * nothing here is INFO.
 */
@Slf4j
@Component
public class HttpPrometheusQueryClient implements PrometheusQueryClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final String baseUrl;

    public HttpPrometheusQueryClient(@Value("${prometheus.url:http://prometheus:9090}") String baseUrl) {
        // Strip any trailing slash so path concatenation is unambiguous.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public PrometheusResult queryInstant(String promql, Instant time) {
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/api/v1/query?query=").append(encode(promql));
        if (time != null) {
            url.append("&time=").append(time.getEpochSecond());
        }
        return execute(url.toString(), promql, PrometheusResult.ResultType.VECTOR);
    }

    @Override
    public PrometheusResult queryRange(String promql, Instant start, Instant end, Duration step) {
        String url = baseUrl + "/api/v1/query_range?query=" + encode(promql)
                + "&start=" + start.getEpochSecond()
                + "&end=" + end.getEpochSecond()
                + "&step=" + step.toSeconds();
        return execute(url, promql, PrometheusResult.ResultType.MATRIX);
    }

    private PrometheusResult execute(String url, String promql, PrometheusResult.ResultType expected) {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Prometheus query transport failure for [{}]: {}", promql, e.getMessage());
            throw new UpstreamPrometheusException("Prometheus query failed: " + e.getMessage(), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Prometheus returned HTTP {} for [{}]: {}", response.statusCode(), promql, response.body());
            throw new UpstreamPrometheusException(
                    "Prometheus returned HTTP " + response.statusCode() + " for query: " + promql);
        }

        PrometheusResult result = parse(response.body(), expected, promql);
        log.debug("Prometheus query [{}] → {} series ({})", promql, result.series().size(), result.resultType());
        return result;
    }

    /**
     * Parse a Prometheus response body. Package-private and free of any HTTP /
     * Spring state so the envelope handling can be unit-tested against canned
     * JSON without a live server (METRICS_API Phase 2 verification).
     *
     * @param body     the raw response body.
     * @param expected the result type the caller expected (used only to label
     *                 the parsed result if Prometheus omitted {@code resultType}).
     * @param promql   the originating query, for error messages / logging only.
     */
    PrometheusResult parse(String body, PrometheusResult.ResultType expected, String promql) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new UpstreamPrometheusException("Unparseable Prometheus response for query: " + promql, e);
        }

        String status = root.path("status").asText("");
        if (!"success".equals(status)) {
            String errorType = root.path("errorType").asText("");
            String error = root.path("error").asText("unknown error");
            throw new UpstreamPrometheusException(
                    "Prometheus error" + (errorType.isEmpty() ? "" : " (" + errorType + ")") + ": " + error);
        }

        JsonNode data = root.path("data");
        PrometheusResult.ResultType resultType = parseResultType(data.path("resultType").asText(""), expected);

        if (resultType == PrometheusResult.ResultType.SCALAR) {
            return new PrometheusResult(resultType, parseScalar(data.path("result")));
        }

        List<PrometheusResult.Series> series = new ArrayList<>();
        JsonNode resultArray = data.path("result");
        if (resultArray.isArray()) {
            for (JsonNode entry : resultArray) {
                series.add(parseSeries(entry, resultType));
            }
        }
        return new PrometheusResult(resultType, series);
    }

    private PrometheusResult.ResultType parseResultType(String wire, PrometheusResult.ResultType expected) {
        return switch (wire) {
            case "vector" -> PrometheusResult.ResultType.VECTOR;
            case "matrix" -> PrometheusResult.ResultType.MATRIX;
            case "scalar" -> PrometheusResult.ResultType.SCALAR;
            default -> expected;
        };
    }

    private List<PrometheusResult.Series> parseScalar(JsonNode result) {
        // Scalar: data.result = [ts, "value"]
        if (result.isArray() && result.size() == 2) {
            PrometheusResult.Sample sample = parseSample(result);
            return List.of(new PrometheusResult.Series(Map.of(), List.of(sample)));
        }
        return List.of();
    }

    private PrometheusResult.Series parseSeries(JsonNode entry, PrometheusResult.ResultType resultType) {
        Map<String, String> labels = new LinkedHashMap<>();
        JsonNode metric = entry.path("metric");
        metric.fields().forEachRemaining(f -> labels.put(f.getKey(), f.getValue().asText()));

        List<PrometheusResult.Sample> samples = new ArrayList<>();
        if (resultType == PrometheusResult.ResultType.VECTOR) {
            JsonNode value = entry.path("value");
            if (value.isArray() && value.size() == 2) {
                samples.add(parseSample(value));
            }
        } else {
            JsonNode values = entry.path("values");
            if (values.isArray()) {
                for (JsonNode v : values) {
                    if (v.isArray() && v.size() == 2) {
                        samples.add(parseSample(v));
                    }
                }
            }
        }
        return new PrometheusResult.Series(labels, samples);
    }

    /**
     * Parse a {@code [timestamp, "value"]} pair. Prometheus serializes sample
     * values as strings, including {@code "NaN"}, {@code "+Inf"}, {@code "-Inf"};
     * non-finite values map to {@code null} so the JSON stays valid (AD-7).
     */
    private PrometheusResult.Sample parseSample(JsonNode pair) {
        long ts = pair.get(0).asLong();
        String raw = pair.get(1).asText();
        Double value;
        try {
            double parsed = Double.parseDouble(raw);
            value = Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            value = null;
        }
        return new PrometheusResult.Sample(ts, value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
