package com.vingame.bot.domain.bot.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.websocketparser.message.ProcessableMessage;
import com.vingame.websocketparser.scenario.PipelineContext;
import com.vingame.websocketparser.scenario.PipelineStage;
import com.vingame.websocketparser.scenario.Scenario;
import com.vingame.websocketparser.scenario.matchers.Qualifier;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.vingame.websocketparser.scenario.Scenario.pipeline;

@Slf4j
public class OutputPrinter {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static PipelineStage<ProcessableMessage, ProcessableMessage> filter(List<Integer> cmd, PipelineContext context) {
        return pipeline(context)
                .filter(cmd.stream()
                        .map(Qualifier::cmd)
                        .reduce(Qualifier::or)
                        .orElse(null)
                );
    }

    public static Scenario defaultOutputPrinter(List<Integer> cmd,  PipelineContext context) {
        return outputPrinter(cmd, log::trace, context);
    }

    /**
     * Builds a raw per-frame printer scenario whose log lines carry the supplied MDC snapshot.
     * The {@code peek} consumer runs on the per-client {@code netty-ws-message-processor-ws-<userName>}
     * pool, which has no MDC of its own. Wrapping the consumer here ensures every
     * {@code User <name>: ...} line in {@code console.log} is tagged with
     * {@code botGroupId}, {@code environmentId}, {@code gameType}, etc., so Promtail
     * can promote them to Loki labels.
     * <p>
     * These raw wire frames now emit at {@code TRACE}, in line with its siblings
     * {@code defaultOutputPrinter} and {@code prettifiedOutputPrinter}. No per-frame WS
     * printer logs above TRACE. This finalizes and supersedes the RESILIENCE_HARDENING
     * Phase P0a demotion — P0a moved only the two sibling printers to TRACE while this
     * (the printer actually wired into {@code BettingMiniGameBot}/{@code SlotMachineBot})
     * stayed at DEBUG and kept flooding, because production runs at the DEBUG default.
     * <p>
     * At the production DEBUG default the raw frames are now silent; the default-visible
     * per-session view comes from {@code SessionAggregationService} (INFO per-session
     * StartGame/EndGame lifecycle summaries, DEBUG 5 s UpdateBet/slot aggregate flush).
     * The {@code User <name>: ...} frames only resurface when {@code com.vingame.bot} is
     * drilled in to TRACE via {@code /actuator/loggers} — the opt-in wire-level escape hatch.
     *
     * @param mdcSnapshot snapshot taken from {@code Bot.mdcSnapshot} at the end of
     *                    {@code Bot.initialize()}; may be {@code null}, in which case
     *                    log lines fall through with whatever MDC the calling thread has.
     */
    public static Scenario debugOutputPrinter(List<Integer> cmd, String name,
                                              PipelineContext context,
                                              Map<String, String> mdcSnapshot) {
        Consumer<String> printer = s -> log.trace("User {}: {}", name, s);
        return outputPrinter(cmd, withMdc(printer, mdcSnapshot), context);
    }

    /**
     * Wraps a {@code Consumer<String>} so the bot's MDC snapshot is applied around
     * each invocation. Mirrors the contract of {@code Bot.mdcConsumer} but inlined
     * here so {@code OutputPrinter} does not need a {@code Bot} reference. See
     * Architecture Decision 7 in {@code docs/plans/LOGGING_PIPELINE_FIX.md}.
     */
    private static Consumer<String> withMdc(Consumer<String> delegate,
                                            Map<String, String> snapshot) {
        if (snapshot == null) {
            return delegate;
        }
        return s -> {
            Map<String, String> stash = MDC.getCopyOfContextMap();
            MDC.setContextMap(snapshot);
            try {
                delegate.accept(s);
            } finally {
                if (stash != null) {
                    MDC.setContextMap(stash);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    private static Scenario outputPrinter(List<Integer> cmd, Consumer<String> printer, PipelineContext context) {
        return filter(cmd, context)
                .map(ProcessableMessage::toString)
                .peek(printer)
                .compile();
    }

    public static Scenario prettifiedOutputPrinter(List<Integer> cmd, PipelineContext context) {
        return filter(cmd, context)
                .map(m -> {
                    try {
                        return mapper.readValue(m.getMessage(), Object.class);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                })
                .map(o -> {
                    try {
                        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(o);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                })
                .peek(log::trace)
                .compile();
    }

}
