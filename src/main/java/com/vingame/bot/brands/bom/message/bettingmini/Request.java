package com.vingame.bot.brands.bom.message.bettingmini;

import com.vingame.bot.brands.bom.message.bettingmini.request.AutoBet;
import com.vingame.bot.brands.bom.message.bettingmini.request.Bet;
import com.vingame.bot.brands.bom.message.bettingmini.request.BetEntryInfo;
import com.vingame.bot.brands.bom.message.bettingmini.request.Chat;
import com.vingame.bot.brands.bom.message.bettingmini.request.FetchAllPlayers;
import com.vingame.bot.brands.bom.message.bettingmini.request.FetchBetHistory;
import com.vingame.bot.brands.bom.message.bettingmini.request.FetchSessionDetail;
import com.vingame.bot.brands.bom.message.bettingmini.request.SubscribeToLobbyMessage;
import com.vingame.webocketparser.message.request.Data;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;

@AllArgsConstructor
public class Request {

    private final String pluginName;
    private final String zoneName;
    private final int cmdPrefix;

    public FetchSessionDetail fetchSessionDetail(long sid) {
        return new FetchSessionDetail(pluginName, zoneName, cmdPrefix + 3009, sid);
    }

    public FetchSessionDetail fetchSessionDetail(long sid, int aid) {
        return new FetchSessionDetail(pluginName, zoneName, cmdPrefix + 3009, sid, aid);
    }

    public SubscribeToLobbyMessage subscribe() {
        return new SubscribeToLobbyMessage(zoneName, pluginName, new Data(cmdPrefix + 3000));
    }

    public Bet bet(long amount, int entryId, long sid) {
        return new Bet(cmdPrefix + 3002, zoneName, pluginName, amount, entryId, sid);
    }

    public FetchBetHistory fetchBetHistory(int assetId, int limit, int skip) {
        return new FetchBetHistory(cmdPrefix + 3004, zoneName, pluginName, assetId, limit, skip);
    }

    public Chat chat(String message) {
        return new Chat(cmdPrefix + 3008, zoneName, pluginName, message);
    }

    public AutoBet autoBet(boolean isMini, Map<Integer, Long> betEntries) {
        List<BetEntryInfo> entries = betEntries.entrySet().stream()
                .map(entry -> BetEntryInfo.of(entry.getKey(), entry.getValue()))
                .toList();
        return new  AutoBet(cmdPrefix + 3015, zoneName, pluginName, isMini, entries);
    }

    public AutoBet autoBet(Map<Integer, Long> betEntries) {
        return autoBet(true, betEntries);
    }

    public FetchAllPlayers fetchAllPlayers(int limit, int offset, Map<String, Boolean> options) {
        return new FetchAllPlayers(cmdPrefix + 3006, zoneName, pluginName, limit, offset, options);
    }
}
