package com.vingame.bot.infrastructure.client.prometheus;

import com.vingame.bot.common.exception.UpstreamPrometheusException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tests the Prometheus envelope parsing against canned JSON (METRICS_API
 * Phase 2 verification): vector, matrix, scalar, empty result, error envelope,
 * and non-finite (NaN/Inf) sample values. No live server is contacted — the
 * package-private {@link HttpPrometheusQueryClient#parse} step is exercised
 * directly.
 */
class HttpPrometheusQueryClientTest {

    private final HttpPrometheusQueryClient client = new HttpPrometheusQueryClient("http://prometheus:9090");

    @Test
    void parsesVectorWithLabelsAndSingleSample() {
        String body = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"__name__":"bots_by_game_status","status":"CONNECTION_AUTHENTICATED"},"value":[1700000000,"7"]},
                  {"metric":{"status":"RECONNECTING"},"value":[1700000000,"2"]}
                ]}}""";

        PrometheusResult result = client.parse(body, PrometheusResult.ResultType.VECTOR, "q");

        assertThat(result.resultType()).isEqualTo(PrometheusResult.ResultType.VECTOR);
        assertThat(result.series()).hasSize(2);

        PrometheusResult.Series first = result.series().get(0);
        assertThat(first.labels())
                .containsEntry("__name__", "bots_by_game_status")
                .containsEntry("status", "CONNECTION_AUTHENTICATED");
        assertThat(first.samples()).hasSize(1);
        assertThat(first.samples().get(0).timestamp()).isEqualTo(1700000000L);
        assertThat(first.samples().get(0).value()).isEqualTo(7.0);

        assertThat(result.series().get(1).samples().get(0).value()).isEqualTo(2.0);
    }

    @Test
    void parsesMatrixWithOrderedSamples() {
        String body = """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"gameName":"BauCua"},"values":[[1700000000,"1.5"],[1700000060,"2.5"],[1700000120,"3.5"]]}
                ]}}""";

        PrometheusResult result = client.parse(body, PrometheusResult.ResultType.MATRIX, "q");

        assertThat(result.resultType()).isEqualTo(PrometheusResult.ResultType.MATRIX);
        assertThat(result.series()).hasSize(1);
        PrometheusResult.Series series = result.series().get(0);
        assertThat(series.labels()).containsEntry("gameName", "BauCua");
        assertThat(series.samples()).hasSize(3);
        assertThat(series.samples().get(0).timestamp()).isEqualTo(1700000000L);
        assertThat(series.samples().get(0).value()).isEqualTo(1.5);
        assertThat(series.samples().get(2).value()).isEqualTo(3.5);
    }

    @Test
    void parsesScalar() {
        String body = """
                {"status":"success","data":{"resultType":"scalar","result":[1700000000,"42"]}}""";

        PrometheusResult result = client.parse(body, PrometheusResult.ResultType.VECTOR, "q");

        assertThat(result.resultType()).isEqualTo(PrometheusResult.ResultType.SCALAR);
        assertThat(result.series()).hasSize(1);
        assertThat(result.series().get(0).samples().get(0).value()).isEqualTo(42.0);
    }

    @Test
    void emptyResultIsValidNonErrorOutcome() {
        String body = """
                {"status":"success","data":{"resultType":"vector","result":[]}}""";

        PrometheusResult result = client.parse(body, PrometheusResult.ResultType.VECTOR, "q");

        assertThat(result.resultType()).isEqualTo(PrometheusResult.ResultType.VECTOR);
        assertThat(result.series()).isEmpty();
    }

    @Test
    void nonFiniteSampleValuesMapToNull() {
        String body = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{},"value":[1700000000,"NaN"]},
                  {"metric":{"a":"b"},"value":[1700000000,"+Inf"]},
                  {"metric":{"a":"c"},"value":[1700000000,"-Inf"]}
                ]}}""";

        PrometheusResult result = client.parse(body, PrometheusResult.ResultType.VECTOR, "q");

        assertThat(result.series()).hasSize(3);
        assertThat(result.series().get(0).samples().get(0).value()).isNull();
        assertThat(result.series().get(1).samples().get(0).value()).isNull();
        assertThat(result.series().get(2).samples().get(0).value()).isNull();
    }

    @Test
    void errorEnvelopeThrowsUpstreamException() {
        String body = """
                {"status":"error","errorType":"bad_data","error":"invalid parameter \\"query\\": unknown function"}""";

        assertThatThrownBy(() -> client.parse(body, PrometheusResult.ResultType.VECTOR, "bad(query)"))
                .isInstanceOf(UpstreamPrometheusException.class)
                .hasMessageContaining("bad_data")
                .hasMessageContaining("unknown function");
    }

    @Test
    void unparseableBodyThrowsUpstreamException() {
        assertThatThrownBy(() -> client.parse("not json at all", PrometheusResult.ResultType.VECTOR, "q"))
                .isInstanceOf(UpstreamPrometheusException.class)
                .hasMessageContaining("Unparseable");
    }
}
