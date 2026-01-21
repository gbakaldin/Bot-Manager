package com.vingame.bot.domain.botgroup.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BotGroupFilter {

    private String name;
    private String environmentId;
    private String gameId;
}
