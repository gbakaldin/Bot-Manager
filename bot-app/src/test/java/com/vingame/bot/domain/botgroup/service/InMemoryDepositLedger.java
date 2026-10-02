package com.vingame.bot.domain.botgroup.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link DepositLedger} with the same conditional semantics as the Mongo one, in memory, plus a
 * journal of every call — the worker tests assert ordering on it ("marker before send", "credit
 * after 200"). Not a mock: the conditions are the point, and a mock would only echo the test.
 */
class InMemoryDepositLedger extends DepositLedger {

    private final Map<String, State> states = new ConcurrentHashMap<>();
    final List<String> journal = new CopyOnWriteArrayList<>();
    /** How many upcoming {@code credit} calls throw as if Mongo were down. */
    volatile int failCredits;
    /** When set, the next {@code markInFlight} throws (and writes nothing). */
    volatile boolean failNextMark;

    InMemoryDepositLedger() {
        super(null);
    }

    void put(String groupId, int deposited, Integer inFlight) {
        states.put(groupId, new State(deposited, inFlight));
    }

    @Override
    public State read(String botGroupId) {
        return states.getOrDefault(botGroupId, new State(0, null));
    }

    @Override
    public synchronized void markInFlight(String botGroupId, int index) {
        journal.add("mark:" + index);
        if (failNextMark) {
            failNextMark = false;
            throw new IllegalStateException("simulated Mongo failure writing the marker");
        }
        State s = read(botGroupId);
        if (s.depositedCount() != index - 1 || s.depositInFlight() != null) {
            throw new IllegalStateException("ledger refused marker " + index + " over " + s);
        }
        states.put(botGroupId, new State(s.depositedCount(), index));
    }

    @Override
    public synchronized void credit(String botGroupId, int index) {
        journal.add("credit:" + index);
        if (failCredits > 0) {
            failCredits--;
            throw new IllegalStateException("simulated Mongo failure on the credit write");
        }
        // Unconditional and monotonic, like the Mongo ledger: $max the count, then clear the
        // marker only if it still names this index.
        State s = read(botGroupId);
        Integer marker = s.depositInFlight() != null && s.depositInFlight() == index ? null : s.depositInFlight();
        states.put(botGroupId, new State(Math.max(s.depositedCount(), index), marker));
    }

    @Override
    public synchronized boolean reassertInFlight(String botGroupId, int index) {
        journal.add("reassert:" + index);
        State s = read(botGroupId);
        if (s.depositedCount() < index && (s.depositInFlight() == null || s.depositInFlight() == index)) {
            states.put(botGroupId, new State(s.depositedCount(), index));
            return true;
        }
        return false;
    }

    @Override
    public synchronized void skip(String botGroupId, int index) {
        journal.add("skip:" + index);
        State s = read(botGroupId);
        if (s.depositedCount() != index - 1 || s.depositInFlight() != null) {
            throw new IllegalStateException("ledger refused skip " + index + " over " + s);
        }
        states.put(botGroupId, new State(index, null));
    }

    @Override
    public synchronized void clearInFlight(String botGroupId, int index) {
        journal.add("clear:" + index);
        State s = read(botGroupId);
        if (s.depositInFlight() != null && s.depositInFlight() == index) {
            states.put(botGroupId, new State(s.depositedCount(), null));
        }
    }

    @Override
    public synchronized boolean resolve(String botGroupId, int index, boolean credited) {
        journal.add("resolve:" + index + ":" + credited);
        State s = read(botGroupId);
        if (s.depositInFlight() == null || s.depositInFlight() != index) {
            return false;
        }
        states.put(botGroupId, new State(credited ? Math.max(s.depositedCount(), index) : s.depositedCount(), null));
        return true;
    }

    @Override
    public synchronized void seedAtLeast(String botGroupId, int count) {
        journal.add("seed:" + count);
        State s = read(botGroupId);
        states.put(botGroupId, new State(Math.max(s.depositedCount(), count), s.depositInFlight()));
    }

    @Override
    public void delete(String botGroupId) {
        journal.add("delete");
        states.remove(botGroupId);
    }
}
