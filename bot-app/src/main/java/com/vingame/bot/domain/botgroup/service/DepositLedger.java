package com.vingame.bot.domain.botgroup.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The authoritative record of which accounts a group's registration-time deposit has funded
 * (BOT_PROVISIONING AD-5 / AD-6). <b>The one place the "never deposit an index twice" decision
 * is made from.</b>
 *
 * <h2>Why not the {@code BotGroup} document, as the plan says</h2>
 * Every whole-document {@code save} of a group — a PATCH, a start, a stop, the health monitor's
 * DEAD write, a scheduled-restart booking — is a Mongo {@code replaceOne} of whatever that caller
 * read. If one of them read the group before the worker credited index {@code k} and wrote after,
 * {@code depositedCount} silently goes back to {@code k-1} and the marker back to {@code null}, and
 * the next pass deposits index {@code k} a second time. {@code persistProgress} documents the same
 * race for {@code registeredCount} and calls it self-correcting — a re-register answers
 * {@code EXISTED}. A deposit has no such property. This collection is written <b>only</b> by
 * targeted, conditional updates from the worker and the retry endpoint, so no stale save can
 * reach it. {@code BotGroup.depositedCount} / {@code depositInFlight} remain as display mirrors.
 *
 * <h2>Conditional writes</h2>
 * {@link #markInFlight} is an upsert conditioned on {@code depositedCount == index-1 &&
 * depositInFlight == null}: if anything else is already recorded, the upsert collides on
 * {@code _id} and throws, and the deposit is never sent. {@link #credit} and
 * {@link #clearInFlight} are conditioned on the marker naming the same index.
 */
@Component
@RequiredArgsConstructor
public class DepositLedger {

    static final String COLLECTION = "botGroupDepositLedger";

    private final MongoTemplate mongoTemplate;

    /** What the ledger says for one group; {@code (0, null)} when nothing was ever recorded. */
    public record State(int depositedCount, Integer depositInFlight) {
        static final State EMPTY = new State(0, null);
    }

    /** The persisted shape. Package-private: nothing outside this class reads it. */
    @Document(collection = COLLECTION)
    static class Entry {
        @Id
        String id;
        int depositedCount;
        Integer depositInFlight;
    }

    public State read(String botGroupId) {
        Entry entry = mongoTemplate.findById(botGroupId, Entry.class, COLLECTION);
        return entry == null ? State.EMPTY : new State(entry.depositedCount, entry.depositInFlight);
    }

    /**
     * Write-ahead marker (AD-6 step 1): record that {@code index}'s deposit is about to be sent.
     * Called from inside the gateway budget's callable, after admission and immediately before
     * the HTTP send; a throw here means the request is <b>not</b> sent.
     *
     * @throws IllegalStateException (or Mongo's duplicate-key exception) if the ledger does not
     *         say "indices {@code 1..index-1} done, nothing in flight"
     */
    public void markInFlight(String botGroupId, int index) {
        Query query = Query.query(Criteria.where("_id").is(botGroupId)
                .and("depositedCount").is(index - 1)
                .and("depositInFlight").is(null));
        // Upsert: a group's first deposit creates its entry. When the entry exists but disagrees,
        // the upsert tries to insert a second document with the same _id and Mongo refuses it —
        // which is exactly the "never send" outcome wanted.
        Entry after = mongoTemplate.findAndModify(query, new Update().set("depositInFlight", index),
                FindAndModifyOptions.options().upsert(true).returnNew(true), Entry.class, COLLECTION);
        if (after == null || after.depositInFlight == null || after.depositInFlight != index) {
            throw new IllegalStateException("deposit ledger for group " + botGroupId
                    + " did not accept the in-flight marker for index " + index);
        }
    }

    /**
     * AD-6 step 2: one write that advances the high-water mark and clears the marker.
     *
     * @throws IllegalStateException if the marker no longer names {@code index}
     */
    public void credit(String botGroupId, int index) {
        long matched = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(botGroupId).and("depositInFlight").is(index)),
                new Update().set("depositedCount", index).set("depositInFlight", null),
                Entry.class, COLLECTION).getMatchedCount();
        if (matched == 0) {
            throw new IllegalStateException("deposit ledger for group " + botGroupId
                    + " lost the in-flight marker for index " + index + " before the credit write");
        }
    }

    /** AD-6 step 3: a definite non-credit; the marker for {@code index} goes. */
    public void clearInFlight(String botGroupId, int index) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(botGroupId).and("depositInFlight").is(index)),
                new Update().set("depositInFlight", null), Entry.class, COLLECTION);
    }

    /**
     * AD-8: an operator's resolution of an unknown outcome. {@code credited} advances the
     * high-water mark to the marker; either way the marker is cleared. Conditioned on the marker
     * still naming {@code index}, so a stale retry cannot resolve a different deposit.
     *
     * @return whether the marker named {@code index} and was resolved
     */
    public boolean resolve(String botGroupId, int index, boolean credited) {
        Update update = new Update().set("depositInFlight", null);
        if (credited) {
            update.max("depositedCount", index);
        }
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(botGroupId).and("depositInFlight").is(index)),
                update, Entry.class, COLLECTION).getMatchedCount() > 0;
    }

    /**
     * AD-9: accounts that existed before a raise are never funded by it. Monotonic
     * ({@code $max}), so it can only ever move the mark forward.
     */
    public void seedAtLeast(String botGroupId, int count) {
        mongoTemplate.upsert(Query.query(Criteria.where("_id").is(botGroupId)),
                new Update().max("depositedCount", count), Entry.class, COLLECTION);
    }

    /** Forget a deleted group. */
    public void delete(String botGroupId) {
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(botGroupId)), Entry.class, COLLECTION);
    }
}
