package com.vingame.bot.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.webocketparser.message.ProcessableMessage;
import com.vingame.webocketparser.message.properties.MessageProperty;
import com.vingame.webocketparser.scenario.PipelineStage;
import com.vingame.webocketparser.scenario.Scenario;
import com.vingame.webocketparser.scenario.matchers.Qualifier;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.vingame.webocketparser.scenario.matchers.Qualifier.qualifier;

@Slf4j
public class OutputPrinter {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static PipelineStage<ProcessableMessage, ProcessableMessage> filter(List<Integer> cmd) {
        return Scenario.pipeline()
                .filter(cmd.stream()
                        .map(i -> qualifier(MessageProperty.CMD, i))
                        .collect(Collectors.toList())
                        .toArray(new Qualifier[0]));
    }

    public static Scenario defaultOutputPrinter(List<Integer> cmd) {
        return outputPrinter(cmd, log::info);
    }

    public static Scenario debugOutputPrinter(List<Integer> cmd, String name) {
        return outputPrinter(cmd, s -> log.debug("User {}: {}", name, s));
    }

    private static Scenario outputPrinter(List<Integer> cmd, Consumer<String> printer) {
        return filter(cmd)
                .map(ProcessableMessage::toString)
                .peek(printer)
                .compile();
    }

    public static Scenario prettifiedOutputPrinter(List<Integer> cmd) {
        return filter(cmd)
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
