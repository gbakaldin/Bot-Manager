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
 * {@code _id} and throws, and the deposit is never sent. {@link #clearInFlight} is conditioned on the marker naming the same index.
 * <p>
 * <b>A credited 200 is recorded unconditionally</b> (review Phase 2, bug 2): {@link #credit}
 * moves the funded mark with {@code $max} filtered by {@code _id} alone and only then clears the
 * marker if it still names the index. Gating the record on the marker let a racing operator answer
 * (or a second JVM) "un-record" a deposit that had in fact gone through, after which the index was
 * sent again. The mark only ever moves forward.
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
     * AD-6 step 2: record a credited deposit. <b>Unconditional and monotonic</b>: {@code $max}
     * the funded mark to {@code index} whatever the marker says, then clear the marker only if it
     * still names {@code index}. Idempotent, so a caller may simply repeat it after a failure.
     */
    public void credit(String botGroupId, int index) {
        mongoTemplate.upsert(Query.query(Criteria.where("_id").is(botGroupId)),
                new Update().max("depositedCount", index), Entry.class, COLLECTION);
        clearInFlight(botGroupId, index);
    }

    /**
     * Put the marker for {@code index} back (review Phase 2, bug 2b) — on an unknown outcome, or
     * when a credit could not be recorded — unless the index is already recorded as done or a
     * different marker is set. Whatever happened to the marker meanwhile, a group that stops on an
     * unknown outcome must stop <em>with</em> its question, so the next pass cannot send again.
     *
     * @return whether the marker now names {@code index}
     */
    public boolean reassertInFlight(String botGroupId, int index) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(botGroupId),
                Criteria.where("depositedCount").lt(index),
                new Criteria().orOperator(Criteria.where("depositInFlight").is(null),
                        Criteria.where("depositInFlight").is(index))));
        return mongoTemplate.updateFirst(query, new Update().set("depositInFlight", index),
                Entry.class, COLLECTION).getMatchedCount() > 0;
    }

    /**
     * Mark {@code index} as done <b>without funding it</b> (BOT_PROVISIONING AD-9, review bug 3):
     * the account answered {@code EXISTED} to a registration this job sent, so it is not ours to
     * fund. Same guard as {@link #markInFlight}: only when the ledger says {@code 1..index-1} done
     * and nothing in flight.
     *
     * @throws IllegalStateException if the ledger did not accept it
     */
    public void skip(String botGroupId, int index) {
        Query query = Query.query(Criteria.where("_id").is(botGroupId)
                .and("depositedCount").is(index - 1)
                .and("depositInFlight").is(null));
        Entry after = mongoTemplate.findAndModify(query, new Update().set("depositedCount", index),
                FindAndModifyOptions.options().upsert(true).returnNew(true), Entry.class, COLLECTION);
        if (after == null || after.depositedCount != index) {
            throw new IllegalStateException("deposit ledger for group " + botGroupId
                    + " did not accept skipping index " + index);
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
