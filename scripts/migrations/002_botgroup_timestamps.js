// ============================================================================
// Migration 002 — BotGroup createdAt / updatedAt backfill
// BOTGROUP_GAME_MANAGEMENT Phase 4 (AD-14 / AD-16)
// ============================================================================
//
// WHAT THIS DOES
//   Backfills `createdAt` and `updatedAt` on every `botGroups` document to a
//   single fixed ISODate (~deploy day) so nothing sorts as N/A on the
//   CREATED_TIME / UPDATED_TIME sort keys (AD-14 / AD-16). The exact value is
//   immaterial — these are throwaway staging test docs; it only needs to be a
//   real instant. Mirrors the Game backfill in 001_game_env_scope.js.
//
// SAFETY
//   - Idempotent: re-running is safe. createdAt is only set when absent so a real
//     create timestamp is never clobbered; updatedAt is (re)stamped.
//
// HOW TO RUN (Releaser)
//   mongosh "mongodb://<host>:<port>/botmanager" scripts/migrations/002_botgroup_timestamps.js
//   (on the staging container: docker exec -i bot-java-mongo-1 mongosh botmanager < 002_botgroup_timestamps.js)
//
// POST-CHECKS
//   db.botGroups.countDocuments({createdAt:{$exists:false}})  -> 0
//   db.botGroups.countDocuments({updatedAt:{$exists:false}})  -> 0
// ============================================================================

(function () {
    // Fixed backfill timestamp (~deploy day). Value is immaterial per AD-14/AD-16;
    // kept identical to migration 001 so games and groups share a coherent epoch.
    var BACKFILL_TS = ISODate("2026-07-03T00:00:00Z");

    var updated = 0;

    db.botGroups.find({}, { _id: 1, createdAt: 1 }).forEach(function (group) {
        var set = { updatedAt: BACKFILL_TS };
        if (group.createdAt === undefined || group.createdAt === null) {
            set.createdAt = BACKFILL_TS;
        }
        db.botGroups.updateOne({ _id: group._id }, { $set: set });
        updated++;
    });

    print("Migration 002 complete: " + updated + " bot group(s) backfilled.");

    // ---- Post-check summary ---------------------------------------------------
    print("Post-check: bot groups missing createdAt = " +
        db.botGroups.countDocuments({ createdAt: { $exists: false } }));
    print("Post-check: bot groups missing updatedAt = " +
        db.botGroups.countDocuments({ updatedAt: { $exists: false } }));
})();
