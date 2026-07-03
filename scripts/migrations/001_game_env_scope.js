// ============================================================================
// Migration 001 — Game env-scoping backfill
// BOTGROUP_GAME_MANAGEMENT Phase 1 (AD-1 / AD-3 / AD-14 / AD-16)
// ============================================================================
//
// WHAT THIS DOES
//   1. Builds the (brandCode, productCode) -> environment._id map from the
//      `environments` collection. Per AD-3 the staging data is unambiguous:
//      every (brandCode, productCode) combo maps to exactly ONE environment.
//   2. Sets `environmentId` on every `games` document from that map, keyed on
//      the game's own (brandCode, productCode). No cloning, no bot-group
//      repointing — each BotGroup already points at a Game _id that resolves to
//      exactly one env.
//   3. Backfills `createdAt` and `updatedAt` on every game to a single fixed
//      ISODate (~deploy day) so nothing sorts as N/A on CREATED_TIME /
//      UPDATED_TIME (AD-14 / AD-16). The exact value is immaterial — these are
//      throwaway staging test docs.
//
// SAFETY
//   - Idempotent: re-running is safe. environmentId is recomputed from the map;
//     createdAt is only set when absent; updatedAt is (re)stamped.
//   - Any game whose (brandCode, productCode) is NOT in the map is LOGGED and
//     left untouched (must be zero on staging per AD-3), never silently skipped.
//   - brandCode / productCode are persisted by Spring Data MongoDB as the enum
//     NAME strings (e.g. "G2", "P_097"), so the map keys on those raw strings.
//
// HOW TO RUN (Releaser)
//   mongosh "mongodb://<host>:<port>/botmanager" scripts/migrations/001_game_env_scope.js
//   (on the staging container: docker exec -i bot-java-mongo-1 mongosh botmanager < 001_game_env_scope.js)
//
// POST-CHECKS (see BOTGROUP_GAME_MANAGEMENT Verification #12)
//   db.games.countDocuments({environmentId:{$exists:false}})  -> 0
//   db.games.countDocuments({createdAt:{$exists:false}})      -> 0
// ============================================================================

(function () {
    // Fixed backfill timestamp (~deploy day). Value is immaterial per AD-14/AD-16;
    // it only needs to be a real instant so nothing sorts as N/A.
    var BACKFILL_TS = ISODate("2026-07-03T00:00:00Z");

    // ---- Step 1: build (brandCode, productCode) -> environment._id map --------
    var envMap = {};        // "brand|product" -> env._id
    var ambiguous = {};     // "brand|product" -> [env._id, ...] when > 1

    db.environments.find({}, { _id: 1, brandCode: 1, productCode: 1 }).forEach(function (env) {
        var key = env.brandCode + "|" + env.productCode;
        if (envMap[key] === undefined) {
            envMap[key] = env._id;
        } else {
            // AD-3 says this must not happen on staging. Record it loudly.
            if (ambiguous[key] === undefined) {
                ambiguous[key] = [envMap[key]];
            }
            ambiguous[key].push(env._id);
        }
    });

    print("Migration 001: built (brand|product) -> env map with " +
        Object.keys(envMap).length + " entries:");
    Object.keys(envMap).forEach(function (k) {
        print("  " + k + " -> " + envMap[k]);
    });

    var ambiguousKeys = Object.keys(ambiguous);
    if (ambiguousKeys.length > 0) {
        print("!! AMBIGUOUS (brand|product) combos map to multiple envs — ABORTING, " +
            "these must be resolved by hand (AD-3 expects zero):");
        ambiguousKeys.forEach(function (k) {
            print("   " + k + " -> " + tojson(ambiguous[k]));
        });
        throw new Error("Aborting migration 001: ambiguous (brand,product)->env mapping");
    }

    // ---- Step 2 + 3: backfill environmentId / createdAt / updatedAt -----------
    var updated = 0;
    var unmapped = 0;

    db.games.find({}, { _id: 1, brandCode: 1, productCode: 1, createdAt: 1 }).forEach(function (game) {
        var key = game.brandCode + "|" + game.productCode;
        var envId = envMap[key];

        var set = {
            updatedAt: BACKFILL_TS
        };
        if (game.createdAt === undefined || game.createdAt === null) {
            set.createdAt = BACKFILL_TS;
        }

        if (envId === undefined) {
            // Not in the map — must be zero on staging. Log, still backfill
            // timestamps, but leave environmentId untouched.
            unmapped++;
            print("!! game " + game._id + " has unmapped (brand|product) '" + key +
                "' — leaving environmentId unset");
        } else {
            set.environmentId = envId;
        }

        db.games.updateOne({ _id: game._id }, { $set: set });
        updated++;
    });

    print("Migration 001 complete: " + updated + " game(s) processed, " +
        unmapped + " with unmapped (brand|product) (expected 0).");

    // ---- Post-check summary ---------------------------------------------------
    print("Post-check: games missing environmentId = " +
        db.games.countDocuments({ environmentId: { $exists: false } }));
    print("Post-check: games missing createdAt     = " +
        db.games.countDocuments({ createdAt: { $exists: false } }));
})();
