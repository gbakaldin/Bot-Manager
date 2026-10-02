package com.vingame.bot.domain.botgroup.service;

import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The <b>Mongo</b> {@link DepositLedger} (BOT_PROVISIONING AD-5 / AD-6) — QA, Phase 2.
 * <p>
 * Every worker test runs against {@link InMemoryDepositLedger}, which re-implements the
 * conditions in Java. Nothing exercised the real class, so deleting the condition on any of its
 * writes — the one thing standing between a stale pass and a second deposit — failed no test.
 * This class pins the exact filter and update documents each method sends, which is the whole of
 * the real ledger's safety: Mongo evaluates them atomically, the Java around them is plumbing.
 * No Mongo here; the documents are the contract.
 */
@DisplayName("DepositLedger — the conditional Mongo writes behind never-deposit-twice")
class DepositLedgerTest {

    private static final String GROUP = "g-1";

    private MongoTemplate mongo;
    private DepositLedger ledger;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        ledger = new DepositLedger(mongo);
    }

    private static DepositLedger.Entry entry(int deposited, Integer inFlight) {
        DepositLedger.Entry e = new DepositLedger.Entry();
        e.id = GROUP;
        e.depositedCount = deposited;
        e.depositInFlight = inFlight;
        return e;
    }

    private void updateFirstMatches(long matched) {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(DepositLedger.Entry.class),
                eq(DepositLedger.COLLECTION)))
                .thenReturn(UpdateResult.acknowledged(matched, matched, null));
    }

    private Query capturedUpdateFirstQuery(ArgumentCaptor<Update> update) {
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateFirst(query.capture(), update.capture(), eq(DepositLedger.Entry.class),
                eq(DepositLedger.COLLECTION));
        return query.getValue();
    }

    private static Document set(Update update) {
        return (Document) update.getUpdateObject().get("$set");
    }

    // ------------------------------------------------------------------ read

    @Test
    @DisplayName("read: no entry is (0, null); an entry reads back as stored")
    void read() {
        assertThat(ledger.read(GROUP)).isEqualTo(new DepositLedger.State(0, null));

        when(mongo.findById(GROUP, DepositLedger.Entry.class, DepositLedger.COLLECTION)).thenReturn(entry(4, 5));
        assertThat(ledger.read(GROUP)).isEqualTo(new DepositLedger.State(4, 5));
    }

    // ------------------------------------------------------------------ markInFlight

    @Test
    @DisplayName("markInFlight: an upsert conditioned on depositedCount == index-1 AND no marker")
    void markInFlightIsConditional() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(DepositLedger.Entry.class), eq(DepositLedger.COLLECTION))).thenReturn(entry(2, 3));

        ledger.markInFlight(GROUP, 3);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        ArgumentCaptor<FindAndModifyOptions> options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongo).findAndModify(query.capture(), update.capture(), options.capture(),
                eq(DepositLedger.Entry.class), eq(DepositLedger.COLLECTION));

        Document filter = query.getValue().getQueryObject();
        assertThat(filter).as("the filter is the guard: anything else recorded => no match => duplicate _id => not sent")
                .containsEntry("_id", GROUP)
                .containsEntry("depositedCount", 2)
                .containsKey("depositInFlight");
        assertThat(filter.get("depositInFlight")).as("marker must be absent/null").isNull();
        assertThat(filter).hasSize(3);

        assertThat(set(update.getValue())).as("only the marker is written")
                .isEqualTo(new Document("depositInFlight", 3));
        assertThat(update.getValue().getUpdateObject()).containsOnlyKeys("$set");
        assertThat(options.getValue().isUpsert()).as("a group's first deposit creates its entry").isTrue();
        assertThat(options.getValue().isReturnNew()).as("the returned doc is what is checked").isTrue();
    }

    @Test
    @DisplayName("markInFlight: no document back, or one naming another index, throws — nothing is sent")
    void markInFlightRejectsAnUnexpectedResult() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(DepositLedger.Entry.class), eq(DepositLedger.COLLECTION)))
                .thenReturn(null, entry(2, null), entry(2, 4));

        assertThatThrownBy(() -> ledger.markInFlight(GROUP, 3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.markInFlight(GROUP, 3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.markInFlight(GROUP, 3)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markInFlight: a Mongo refusal (duplicate _id on the upsert) propagates")
    void markInFlightPropagatesTheCollision() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(DepositLedger.Entry.class), eq(DepositLedger.COLLECTION)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("E11000"));

        assertThatThrownBy(() -> ledger.markInFlight(GROUP, 1))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------ credit

    @Test
    @DisplayName("credit: ONE write, conditioned on the marker naming index, that advances the mark AND clears the marker")
    void creditAdvancesAndClearsInOneConditionalWrite() {
        updateFirstMatches(1);

        ledger.credit(GROUP, 3);

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        Query query = capturedUpdateFirstQuery(update);
        assertThat(query.getQueryObject()).isEqualTo(new Document("_id", GROUP).append("depositInFlight", 3));
        Document expected = new Document("depositedCount", 3);
        expected.put("depositInFlight", null);
        assertThat(set(update.getValue())).isEqualTo(expected);
    }

    @Test
    @DisplayName("credit: no match (the marker was lost) throws, so the worker stops the group")
    void creditWithoutMatchThrows() {
        updateFirstMatches(0);

        assertThatThrownBy(() -> ledger.credit(GROUP, 3)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index 3");
    }

    // ------------------------------------------------------------------ clearInFlight

    @Test
    @DisplayName("clearInFlight: conditioned on the marker naming index, clears it, never touches the count")
    void clearInFlightNeverAdvances() {
        updateFirstMatches(1);

        ledger.clearInFlight(GROUP, 3);

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        Query query = capturedUpdateFirstQuery(update);
        assertThat(query.getQueryObject()).isEqualTo(new Document("_id", GROUP).append("depositInFlight", 3));
        Document expected = new Document();
        expected.put("depositInFlight", null);
        assertThat(set(update.getValue())).isEqualTo(expected);
        assertThat(update.getValue().getUpdateObject()).containsOnlyKeys("$set");
    }

    // ------------------------------------------------------------------ resolve

    @Test
    @DisplayName("resolve(credited): conditioned on the marker, $max the count to index, clear the marker")
    void resolveCredited() {
        updateFirstMatches(1);

        assertThat(ledger.resolve(GROUP, 2, true)).isTrue();

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        Query query = capturedUpdateFirstQuery(update);
        assertThat(query.getQueryObject()).isEqualTo(new Document("_id", GROUP).append("depositInFlight", 2));
        Document expectedSet = new Document();
        expectedSet.put("depositInFlight", null);
        assertThat(set(update.getValue())).isEqualTo(expectedSet);
        assertThat(update.getValue().getUpdateObject().get("$max"))
                .as("monotonic: an answer can never move the mark back")
                .isEqualTo(new Document("depositedCount", 2));
    }

    @Test
    @DisplayName("resolve(not credited): clears the marker and nothing else, so the index is sent once more")
    void resolveNotCredited() {
        updateFirstMatches(1);

        assertThat(ledger.resolve(GROUP, 2, false)).isTrue();

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        Query query = capturedUpdateFirstQuery(update);
        assertThat(query.getQueryObject()).isEqualTo(new Document("_id", GROUP).append("depositInFlight", 2));
        assertThat(update.getValue().getUpdateObject()).containsOnlyKeys("$set");
        assertThat(set(update.getValue())).containsOnlyKeys("depositInFlight");
    }

    @Test
    @DisplayName("resolve: a stale answer (marker no longer names index) matches nothing and reports false")
    void resolveStale() {
        updateFirstMatches(0);

        assertThat(ledger.resolve(GROUP, 2, true)).isFalse();
    }

    // ------------------------------------------------------------------ seedAtLeast / delete

    @Test
    @DisplayName("seedAtLeast: an upsert with $max on the count — never $set, never the marker")
    void seedIsMonotonicUpsert() {
        ledger.seedAtLeast(GROUP, 7);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).upsert(query.capture(), update.capture(), eq(DepositLedger.Entry.class),
                eq(DepositLedger.COLLECTION));
        assertThat(query.getValue().getQueryObject()).isEqualTo(new Document("_id", GROUP));
        assertThat(update.getValue().getUpdateObject())
                .isEqualTo(new Document("$max", new Document("depositedCount", 7)));
    }

    @Test
    @DisplayName("delete removes this group's entry only")
    void delete() {
        ledger.delete(GROUP);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).remove(query.capture(), eq(DepositLedger.Entry.class), eq(DepositLedger.COLLECTION));
        assertThat(query.getValue().getQueryObject()).isEqualTo(new Document("_id", GROUP));
    }
}
