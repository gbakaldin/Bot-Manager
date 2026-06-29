package com.vingame.bot.infrastructure.client.prometheus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-added probes of <b>partial / malformed</b> {@code data.result} payloads that
 * the dev's parse test did not cover — wrong-arity value tuples, a vector entry
 * missing its {@code value}, a matrix entry missing its {@code values}, a scalar
 * of the wrong arity, and a missing {@code resultType} (falling back to the
 * caller's expectation). The contract under test: the parser is defensive and
 * yields an empty/partial-but-valid result rather than throwing on a structurally
 * odd-but-JSON-valid body.
 */
@DisplayName("HttpPrometheusQueryClient.parse — malformed payloads")
class HttpPrometheusQueryClientMalformedTest {

    private final HttpPrometheusQueryClient client = new HttpPrometheusQueryClient("http://prometheus:9090");

    @Test
    @DisplayName("vector entry with a wrong-arity value tuple yields a series with no samples")
    void vectorWrongArityValueDropsSample() {
        String body = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"status":"OK"},"value":[1700000000]}
                ]}}""";

        PrometheusResult result = client.parse(body, PrometheusResult.ResultType.VECTOR, "q");

        assertThat(result.series()).hasSize(1);
        assertThat(result.series().get(0).samples()).isEmpty();
    }

    @Test
    @DisplayName("vector entry missing its value field yields a series with no samples")
    void vectorMissingValueDropsSample() {
        String body = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"status":"OK"}}
                ]}}""";

        PrometheusResult result = client.parse(body, PrometheusResult.ResultType.VECTOR, "q");

        assertThat(result.series()).hasSize(1);
        assertThat(result.series().get(0).samples()).isEmpty();
    }

    @Test
    @DisplayName("matrix entry with a malformed values array keeps only the well-formed tuples")
    void matrixSkipsMalformedTuples() {
        String body = """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"reason":"x"},"values":[[1700000000,"1.0"],[1700000060],[1700000120,"3.0"]]}
                ]}}""";

        PrometheusResult result = client.parse(body, PrometheusResult.ResultType.MATRIX, "q");

        assertThat(result.series()).hasSize(1);
        // The 1-element tuple in the middle is skipped; the two valid ones survive.
        assertThat(result.series().get(0).samples()).hasSize(2);
        assertThat(result.series().get(0).samples().get(0).value()).isEqualTo(1.0);
        assertThat(result.series().get(0).samples().get(1).value()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("matrix entry missing its values field yields a series with no samples")
    void matrixMissingValuesDropsSamples() {
        String body = """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"reason":"x"}}
                ]}}""";

        PrometheusResult result = client.parse(body, PrometheusResult.ResultType.MATRIX, "q");

        assertThat(result.series()).hasSize(1);
        assertThat(result.series().get(0).samples()).isEmpty();
    }

    @Test
    @DisplayName("scalar with wrong arity yields no series rather than throwing")
    void scalarWrongArityYieldsNoSeries() {
        String body = """
                {"status":"success","data":{"resultType":"scalar","result":[1700000000]}}""";

        PrometheusResult result = client.parse(body, PrometheusResult.ResultType.VECTOR, "q");

        assertThat(result.resultType()).isEqualTo(PrometheusResult.ResultType.SCALAR);
        assertThat(result.series()).isEmpty();
    }

    @Test
    @DisplayName("missing resultType falls back to the caller's expected type")
    void missingResultTypeFallsBackToExpected() {
        String body = """
                {"status":"success","data":{"result":[
                  {"metric":{},"value":[1700000000,"1"]}
                ]}}""";

        PrometheusResult result = client.parse(body, PrometheusResult.ResultType.VECTOR, "q");

        assertThat(result.resultType()).isEqualTo(PrometheusResult.ResultType.VECTOR);
        assertThat(result.series().get(0).samples().get(0).value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a result that is not an array yields an empty series list")
    void nonArrayResultYieldsEmpty() {
        String body = """
                {"status":"success","data":{"resultType":"vector","result":{}}}""";

        PrometheusResult result = client.parse(body, PrometheusResult.ResultType.VECTOR, "q");

        assertThat(result.series()).isEmpty();
    }

    @Test
    @DisplayName("a body with no status field is treated as an error envelope")
    void missingStatusTreatedAsError() {
        String body = "{\"data\":{\"resultType\":\"vector\",\"result\":[]}}";

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.parse(body, PrometheusResult.ResultType.VECTOR, "q"))
                .isInstanceOf(com.vingame.bot.common.exception.UpstreamPrometheusException.class);
    }
}
