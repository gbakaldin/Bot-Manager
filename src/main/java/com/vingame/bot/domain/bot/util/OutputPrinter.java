package com.vingame.bot.domain.bot.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.websocketparser.message.ProcessableMessage;
import com.vingame.websocketparser.scenario.PipelineContext;
import com.vingame.websocketparser.scenario.PipelineStage;
import com.vingame.websocketparser.scenario.Scenario;
import com.vingame.websocketparser.scenario.matchers.Qualifier;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
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
        return outputPrinter(cmd, log::info, context);
    }

    public static Scenario debugOutputPrinter(List<Integer> cmd, String name, PipelineContext context) {
        return outputPrinter(cmd, s -> log.info("User {}: {}", name, s), context);
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
                .peek(log::info)
                .compile();
    }

}
