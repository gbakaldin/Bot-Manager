package com.vingame.bot.domain.bot.util;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class SessionIdStore {
    private long sessionId;

    public long get() {
        return sessionId;
    }

    public void set(long sessionId) {
        this.sessionId = sessionId;
    }
}
