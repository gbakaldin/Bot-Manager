package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.Body;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@AllArgsConstructor
public class Request {

    private final String pluginName;
    private final String zoneName;
    private final int cmdPrefix;

    public SubscribeToLobbyMessage subscribe() {
        return new SubscribeToLobbyMessage(zoneName, pluginName, new Body(cmdPrefix + 3000));
    }

    public Bet bet(long amount, int entryId, long sid) {
        return new Bet(cmdPrefix + 3002, zoneName, pluginName, amount, entryId, sid);
    }

    public Chat chat(String message) {
        return new Chat(cmdPrefix + 3008, zoneName, pluginName, message);
    }

    public AutoBet autoBet(boolean isMini, Map<Integer, Long> betEntries) {
        List<BetEntryInfo> entries = betEntries.entrySet().stream()
                .map(entry -> BetEntryInfo.of(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
        return new  AutoBet(cmdPrefix + 3015, zoneName, pluginName, isMini, entries);
    }
}
