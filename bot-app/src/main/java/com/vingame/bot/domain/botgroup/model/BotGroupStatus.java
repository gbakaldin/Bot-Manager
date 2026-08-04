package com.vingame.bot.domain.botgroup.model;

public enum BotGroupStatus {

    ACTIVE, //The bot group is currently running
    STOPPED, //The bot group has been stopped by the admin user
    DEAD //The bot group has crashed or become unresponsive
}
