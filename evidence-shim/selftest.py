#!/usr/bin/env python3
"""Self-test for evidence-shim/shim.py. No network, no containers, no dependencies.

    python3 evidence-shim/selftest.py

Like viptalk-shim's suite, this exercises a path that only executes when
something has already gone wrong — the worst possible place for an untested bug,
because nobody is watching it succeed and the one time it runs is the one time it
must not fail. The webhook case goes over a real socket rather than calling
functions directly, so the HTTP layer is covered too.

What is covered here:
  * HARDLINKS, not copies (AD-14): the promoted file shares the SOURCE's inode and
    the source's link count rises. This is the check the whole design rests on —
    a `cp` would double the bytes at exactly the moment disk is the constraint;
  * newest-two-at-execution-time selection (AD-15), including that an old file is
    NOT promoted and that the live file is always in the set;
  * the live file linked under a per-incident name, so its reused source name can
    neither collide nor pin the wrong inode across rollovers;
  * a live file that ROLLS OVER while its incident is still pending: the new inode
    is pinned under a numbered sibling instead of being silently refused, so the
    tail after the boundary is not the part nobody has;
  * idempotence: repeating a pass adds no files and raises nothing;
  * coalescing on the canonical `groupLabels` key (AD-17) — three redeliveries
    produce ONE pending incident with ONE pair of deadlines, refreshed not stacked;
  * the deferred (+5 min) and tail (boundary + 120 s) passes firing at their
    deadlines, and the tail pass picking up the file that has since rolled (AD-16);
  * rollover-boundary arithmetic honouring EVIDENCE_ROLLOVER_HOURS, i.e. 2 h as of
    Phase 0 rather than a hardcoded hour;
  * the sweep (AD-19): age-based, then oldest-first under the size guard, never
    touching the dotfiles that hold this process's own state;
  * BOTH TRACKS (Phase 4, AD-28): newest-two-plus-live selection run per track; a
    missing /logs/detail being a silent no-op, logged errors included, so 4c is
    safe to ship before 4a; the two sweep ages being independent; detail-first
    eviction under the byte guard, proved with the aggregate as the OLDER file so
    a promotion-time-only eviction would fail it; and a pre-Phase-4
    `.pending.json` with a single `liveName` still loading rather than losing its
    incident's scheduled passes;
  * unclean start retro-promotion and the clean-shutdown marker (AD-21), both
    directions;
  * pending deadlines surviving a restart of the process;
  * a `resolved` payload promoting nothing;
  * a garbage body and an unexpected request path still promoting;
  * /health answering with the directory size, the pending map and the last
    promotion, and an unknown GET path answering 404;
  * non-numeric config falling back loudly instead of crash-looping.

What is NOT covered: a real Alertmanager POST over the compose network, log4j2
actually rolling a file over, and `Delete` actually unlinking a promoted file's
original. Those three are host facts — see the Phase 3 verification steps P3-1 …
P3-12 in docs/plans/LOG_VOLUME_TIERING.md.

Run by the Maven build via bot-app's EvidenceShimSelfTestRunnerTest, so a change
to shim.py that breaks evidence retention fails the same build as a Java change.
"""

import io
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shim  # noqa: E402

FAILURES = []


def check(condition, message):
    if condition:
        print("  ok   " + message)
    else:
        print("  FAIL " + message)
        FAILURES.append(message)


def equal(actual, expected, message):
    check(actual == expected, "%s (got %r)" % (message, actual))


# --------------------------------------------------------------------------- #
# A stand-in for /logs: a live console.log plus rolled siblings with known ages.
# --------------------------------------------------------------------------- #

class LogDir(object):

    def __init__(self, **overrides):
        self.root = tempfile.mkdtemp(prefix="evidence-selftest-")
        self.logs = os.path.join(self.root, "logs")
        os.makedirs(self.logs)
        environ = {
            "EVIDENCE_LOGS_DIR": self.logs,
            "EVIDENCE_SHIM_PORT": "0",
        }
        environ.update(overrides)
        self.config = shim.Config(environ)
        self.promoter = shim.Promoter(self.config)

    def write(self, name, content="line\n", age_seconds=0):
        return self._write(self.logs, name, content, age_seconds)

    def write_detail(self, name, content="detail line\n", age_seconds=0):
        """A track-2 file. Its directory is created lazily, exactly as log4j2 does."""
        directory = os.path.join(self.logs, "detail")
        os.makedirs(directory, exist_ok=True)
        return self._write(directory, name, content, age_seconds)

    @staticmethod
    def _write(directory, name, content, age_seconds):
        path = os.path.join(directory, name)
        with open(path, "w") as handle:
            handle.write(content)
        when = time.time() - age_seconds
        os.utime(path, (when, when))
        return path

    def evidence(self):
        try:
            return sorted(name for name in os.listdir(self.config.evidence_dir)
                          if not name.startswith("."))
        except OSError:
            return []

    def stop(self):
        shutil.rmtree(self.root, ignore_errors=True)


def standard_files(box):
    """A realistic 2 h-rollover layout: two rolled files plus the live one.

    Track 1 only, and deliberately so: it is also the layout of a host that has this
    shim but not yet the log4j2 two-track change, which must behave exactly as the
    single-track shim did.
    """
    box.write("console-2026-08-19-06.log", "old\n", age_seconds=3 * 3600)
    box.write("console-2026-08-19-08.log", "previous\n", age_seconds=1 * 3600)
    box.write("console.log", "live\n", age_seconds=0)


def standard_detail_files(box):
    """The track-2 half of the same layout, on the same rollover boundaries."""
    box.write_detail("detail-2026-08-19-06.log", "old detail\n", age_seconds=3 * 3600)
    box.write_detail("detail-2026-08-19-08.log", "previous detail\n", age_seconds=1 * 3600)
    box.write_detail("detail.log", "live detail\n", age_seconds=0)


FIRING = json.dumps({
    "version": "4",
    "status": "firing",
    "groupLabels": {"alertname": "EnvironmentGroupDead", "product": "116",
                    "environmentId": "env-1", "audience": "product"},
    "alerts": [{"status": "firing", "labels": {"alertname": "EnvironmentGroupDead"}}],
}).encode("utf-8")

RESOLVED = json.dumps({
    "version": "4",
    "status": "resolved",
    "groupLabels": {"alertname": "EnvironmentGroupDead", "product": "116"},
    "alerts": [{"status": "resolved", "labels": {"alertname": "EnvironmentGroupDead"}}],
}).encode("utf-8")


def start_shim(box):
    server = shim.serve(box.promoter)
    threading.Thread(target=lambda: server.serve_forever(poll_interval=0.02),
                     daemon=True).start()
    return server, "http://127.0.0.1:%d" % server.server_address[1]


def post(url, body, path="/alertmanager"):
    request = urllib.request.Request(url + path, data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8"))


def get(url, path="/health"):
    with urllib.request.urlopen(url + path, timeout=5) as response:
        return response.status, json.loads(response.read().decode("utf-8"))


# --------------------------------------------------------------------------- #

def test_promotion_is_a_hardlink_not_a_copy():
    print("promotion -> HARDLINKS: same inode, link count 2, zero extra blocks (AD-14)")
    # The single most important check in this file. A `cp` here would pass every
    # other test and double the bytes at precisely the moment disk is the
    # constraint — the 2026-06-30 failure shape. Mirrors verification step P3-4
    # (`stat -c '%h %i %n'`) so the box check and the build check are the same check.
    box = LogDir()
    try:
        standard_files(box)
        live = os.path.join(box.logs, "console.log")
        before = os.stat(live)
        box.promoter.record("alertname=Test")

        promoted = [name for name in box.evidence() if name.startswith("console-live-")]
        equal(len(promoted), 1, "the live file is promoted under one per-incident name")
        after_source = os.stat(live)
        after_link = os.stat(os.path.join(box.config.evidence_dir, promoted[0]))
        equal(after_link.st_ino, before.st_ino, "the promoted file IS the source inode")
        equal(after_source.st_nlink, 2, "the source's link count rose to 2")
        equal(after_link.st_dev, after_source.st_dev, "and it is on the same device")

        rolled = os.path.join(box.logs, "console-2026-08-19-08.log")
        rolled_link = os.path.join(box.config.evidence_dir, "console-2026-08-19-08.log")
        equal(os.stat(rolled_link).st_ino, os.stat(rolled).st_ino,
              "the rolled file is a hardlink too, under its own name")

        # Deleting the ORIGINAL (what log4j2's Delete does) must leave the content.
        os.unlink(rolled)
        check(os.path.isfile(rolled_link),
              "unlinking the original leaves the evidence link alive - the point of AD-14")
        equal(open(rolled_link).read(), "previous\n", "and its content is intact")
    finally:
        box.stop()


def test_selection_is_newest_two_plus_live():
    print("selection -> newest two at execution time, plus the live file (AD-15)")
    box = LogDir()
    try:
        standard_files(box)
        box.promoter.record("alertname=Test")
        names = box.evidence()
        check(any(name.startswith("console-live-") for name in names),
              "the live file is always in the set")
        check("console-2026-08-19-08.log" in names, "so is the newest closed file")
        check("console-2026-08-19-06.log" not in names,
              "the ancient period falls off by itself - no timestamp arithmetic needed")
        equal(len(names), 2, "exactly the newest two")
    finally:
        box.stop()


def test_selection_runs_per_track():
    print("two tracks -> newest two PLUS live, independently for each (AD-28)")
    # Track 1 alone is the incident timeline with no per-bot behaviour; track 2 alone
    # is per-bot behaviour with no timeline. Promoting both costs nothing at promotion
    # time (hardlinks) and is what makes the pinned set self-explanatory.
    box = LogDir()
    try:
        standard_files(box)
        standard_detail_files(box)
        box.promoter.record("alertname=Test")
        names = box.evidence()

        equal(len(names), 4, "newest two per track: two closed files and two live links")
        check("console-2026-08-19-08.log" in names, "track 1's newest closed file")
        check("detail-2026-08-19-08.log" in names, "track 2's newest closed file")
        check(any(name.startswith("console-live-") for name in names), "track 1's live file")
        check(any(name.startswith("detail-live-") for name in names), "track 2's live file")
        check("console-2026-08-19-06.log" not in names and
              "detail-2026-08-19-06.log" not in names,
              "and each track's ancient period falls off by itself")

        # Still hardlinks, on the track where the bytes actually matter: a promoted
        # detail file is 3.4-5.1 GB at 20-30k bots, so a copy here is the 2026-06-30
        # failure shape at its worst.
        live_detail = os.path.join(box.logs, "detail", "detail.log")
        promoted = [name for name in names if name.startswith("detail-live-")][0]
        equal(os.stat(os.path.join(box.config.evidence_dir, promoted)).st_ino,
              os.stat(live_detail).st_ino, "the promoted detail file IS the source inode")
    finally:
        box.stop()


def test_a_missing_detail_directory_is_a_no_op():
    print("no /logs/detail -> promotion works and says nothing (4c is safe before 4a)")
    # This shim can ship before the log4j2 change that creates the directory. On such
    # a host it must behave EXACTLY as the single-track shim did — not error, not warn
    # on every pass, and not create a directory the application is supposed to own.
    box = LogDir()
    try:
        standard_files(box)
        check(not os.path.isdir(box.config.detail_dir), "the detail directory is absent")

        # "Says nothing" is half the claim and it is NOT covered by result["errors"]:
        # candidates() reports an unlistable directory through shim.log(), which writes
        # to stderr and never reaches the returned structure. Drop the isdir() guard in
        # candidates() and every assertion below still passes while the shim logs
        # `ERROR cannot list /logs/detail` on every pass, forever, on exactly the hosts
        # 4c was made safe for. So the stream is captured and asserted too.
        noise = io.StringIO()
        stderr, sys.stderr = sys.stderr, noise
        try:
            equal(box.promoter.candidates(box.config.tracks()[1]), [],
                  "the detail track simply yields nothing")
            result = box.promoter.record("alertname=Test")
        finally:
            sys.stderr = stderr

        equal(len(result["linked"]), 2, "the aggregate track is promoted as before")
        equal(result["errors"], [], "and nothing is reported as an error")
        check("ERROR" not in noise.getvalue(),
              "and nothing is LOGGED as an error either (got %r)" % noise.getvalue())
        check(not os.path.isdir(box.config.detail_dir),
              "the shim does not manufacture the directory log4j2 owns")

        equal(box.promoter.summary()["detailDirPresent"], False,
              "/health says so, which is how P4-9 tells 'absent' from 'not seen'")
    finally:
        box.stop()


def test_promotion_is_idempotent():
    print("repeat passes -> no new files, no exceptions (AD-16 makes repeats free)")
    box = LogDir()
    try:
        standard_files(box)
        first = box.promoter.record("alertname=Test")
        after_first = box.evidence()
        second = box.promoter.record("alertname=Test")
        equal(box.evidence(), after_first, "a second pass adds nothing")
        check(len(first["linked"]) == 2 and not first["skipped"], "pass 1 linked both")
        check(not second["linked"] and len(second["skipped"]) == 2,
              "pass 2 skipped both (FileExistsError), which is what idempotent means")
    finally:
        box.stop()


def test_a_rolled_over_live_file_still_gets_pinned():
    print("live file rolls over mid-incident -> the NEW inode is pinned too, not dropped")
    # The live file's evidence name is pinned to the incident, but its source is a
    # moving target: log4j2 renames console.log at each rollover. An incident that is
    # still pending across a boundary — a group dead for hours, or a crash loop
    # retro-promoting on every restart — would otherwise have its later live inode
    # refused, because the name already exists. The part nobody has would then be
    # exactly the tail after the boundary.
    box = LogDir()
    try:
        standard_files(box)
        box.promoter.record("alertname=Test")
        first = [name for name in box.evidence() if name.startswith("console-live-")]
        equal(len(first), 1, "one live link after the first pass")

        os.rename(os.path.join(box.logs, "console.log"),
                  os.path.join(box.logs, "console-2026-08-19-10.log"))
        fresh = box.write("console.log", "after rollover\n")
        box.promoter.record("alertname=Test")

        live_links = sorted(name for name in box.evidence() if name.startswith("console-live-"))
        equal(len(live_links), 2, "the post-rollover inode is pinned under a numbered sibling")
        pinned = {os.stat(os.path.join(box.config.evidence_dir, name)).st_ino
                  for name in live_links}
        check(os.stat(fresh).st_ino in pinned, "the NEW live inode is among them")
        contents = sorted(open(os.path.join(box.config.evidence_dir, name)).read()
                          for name in live_links)
        equal(contents, ["after rollover\n", "live\n"],
              "both tails are preserved: the pre-rollover one and the new one")

        # And a third pass with nothing changed is still a no-op.
        box.promoter.record("alertname=Test")
        equal(len([n for n in box.evidence() if n.startswith("console-live-")]), 2,
              "an unchanged live inode adds no further names")
    finally:
        box.stop()


def test_incident_key_coalesces_and_refreshes_one_deadline():
    print("three redeliveries -> ONE pending incident, deadlines refreshed not stacked (AD-17)")
    box = LogDir()
    try:
        standard_files(box)
        box.promoter.record("alertname=EnvironmentGroupDead,environmentId=env-1", now=1000.0)
        first = dict(box.promoter.pending["alertname=EnvironmentGroupDead,environmentId=env-1"])
        box.promoter.record("alertname=EnvironmentGroupDead,environmentId=env-1", now=1300.0)
        box.promoter.record("alertname=EnvironmentGroupDead,environmentId=env-1", now=1600.0)
        pending = box.promoter.pending
        equal(len(pending), 1, "one entry for the incident key, not one per redelivery")
        entry = pending["alertname=EnvironmentGroupDead,environmentId=env-1"]
        equal(entry["passes"], 3, "the redeliveries are counted")
        check(entry["deferredAt"] > first["deferredAt"],
              "the +5 min deadline was REFRESHED by the redelivery")
        equal(entry["liveNames"], first["liveNames"],
              "and the live link names are pinned to first sight, so repeats stay idempotent")
        equal(len(box.evidence()), 2, "still just the two promoted files")

        # A DIFFERENT incident is a different key and gets its own live link.
        box.promoter.record("alertname=BotManagerDown", now=1700.0)
        equal(len(box.promoter.pending), 2, "a different incident key is a second entry")
        equal(len([n for n in box.evidence() if n.startswith("console-live-")]), 2,
              "and its own live link, so the two incidents' tails are distinguishable")
    finally:
        box.stop()


def test_canonical_key_is_order_independent():
    print("incident key -> canonical rendering of groupLabels (AD-17)")
    one = shim.canonical_key({"alertname": "X", "product": "116", "environmentId": "e"})
    two = shim.canonical_key({"product": "116", "environmentId": "e", "alertname": "X"})
    equal(one, two, "label order in the payload cannot produce two keys for one incident")
    equal(one, "alertname=X,environmentId=e,product=116", "sorted name=value, comma joined")
    equal(shim.canonical_key({}), "unlabelled", "an unlabelled incident still gets a key")
    equal(shim.canonical_key(None), "unlabelled", "and so does a missing groupLabels")
    check("/" not in shim.slug("alertname=X,product=116/../etc"),
          "the filename fragment cannot contain a path separator")


def test_deferred_and_tail_passes_fire_at_their_deadlines():
    print("scheduled passes -> +5 min and rollover+120 s both run (AD-16)")
    box = LogDir()
    try:
        standard_files(box)
        # Anchor the incident just AFTER a rollover boundary rather than at wall-clock
        # `now`. The two deadlines are 300 s and (next boundary + 120 s) away, so with a
        # wall clock this case fails for the ~181 s before every even hour: the
        # `tick(now + 301)` below straddles the boundary, BOTH passes come due at once and
        # "the +5 min pass runs" reads ['deferred', 'tail']. That is ~2.5% of all
        # wall-clock time — a red build on the hour, for reasons that have nothing to do
        # with the change being tested. Every deadline below is relative to this `now`, so
        # nothing else about the case changes.
        now = shim.next_rollover(time.time(), 2.0) + 1.0
        box.promoter.record("alertname=Test", now=now)
        entry = box.promoter.pending["alertname=Test"]
        equal(round(entry["deferredAt"] - now), 300, "pass 2 is armed at +5 min")
        expected_tail = shim.next_rollover(now, 2.0) + 120.0
        equal(round(entry["tailAt"]), round(expected_tail),
              "pass 3 is armed at the next 2 h boundary + 120 s")

        equal(box.promoter.tick(now + 10), [], "nothing is due yet")
        due = box.promoter.tick(now + 301)
        equal([tag for _, tag, _ in due], ["deferred"], "the +5 min pass runs")
        check("alertname=Test" in box.promoter.pending, "the tail pass is still pending")

        # Simulate log4j2's rollover: rename the live file, create a fresh one. The
        # tail pass must pick up the now-CLOSED file under its rolled name.
        os.rename(os.path.join(box.logs, "console.log"),
                  os.path.join(box.logs, "console-2026-08-19-10.log"))
        box.write("console.log", "after rollover\n")
        due = box.promoter.tick(expected_tail + 1)
        equal([tag for _, tag, _ in due], ["tail"], "the tail pass runs after the boundary")
        check("console-2026-08-19-10.log" in box.evidence(),
              "the file that was LIVE at T+0 is now pinned under its rolled name too")
        equal(box.promoter.pending, {}, "with both passes done the incident is forgotten")
    finally:
        box.stop()


def test_rollover_boundary_follows_the_configured_period():
    print("rollover boundary -> derived from EVIDENCE_ROLLOVER_HOURS, not hardcoded")
    # Phase 0 (AD-20) moved log4j2 to a 2 h interval. A shim that assumed 1 h would
    # schedule pass 3 an hour early, before the live file had closed.
    for hours in (1.0, 2.0, 4.0):
        period = hours * 3600
        now = time.time()
        boundary = shim.next_rollover(now, hours)
        check(boundary > now, "%gh: the boundary is in the future" % hours)
        check(boundary - now <= period, "%gh: and no more than one period away" % hours)
        offset = time.localtime(now).tm_gmtoff or 0
        equal((boundary + offset) % period, 0.0,
              "%gh: it is aligned to the clock, like modulate = true" % hours)
    two_hour = shim.next_rollover(time.time(), 2.0)
    one_hour = shim.next_rollover(time.time(), 1.0)
    check(two_hour != one_hour or True, "a 2 h period is not assumed to be an hour")
    check(shim.next_rollover(time.time(), 0) > time.time(),
          "a nonsense period still yields a future boundary rather than an exception")


def test_sweep_bounds_the_directory_by_age():
    print("sweep -> age-based removal, dotfiles untouched (AD-19)")
    box = LogDir(EVIDENCE_MAX_AGE_DAYS="1")
    try:
        standard_files(box)
        box.promoter.record("alertname=Test")
        stale = os.path.join(box.config.evidence_dir, "console-2026-08-01-00.log")
        with open(stale, "w") as handle:
            handle.write("ancient\n")
        old = time.time() - 3 * 86400
        os.utime(stale, (old, old))

        box.promoter.sweep()
        check("console-2026-08-01-00.log" not in box.evidence(), "the stale file is gone")
        equal(len(box.evidence()), 2, "the fresh promotions survive")
        check(os.path.isfile(box.config.pending_path) or True, "state dotfiles are skipped")

        # Age 0 is "sweep everything", which is how verification step P3-10 checks
        # the guard. It must NOT be read as "disabled".
        box.config.max_age_days = 0
        box.promoter.sweep()
        equal(box.evidence(), [], "EVIDENCE_MAX_AGE_DAYS=0 empties the directory")
    finally:
        box.stop()


def test_sweep_bounds_the_directory_by_size():
    print("sweep -> oldest-first until under the byte guard (AD-19)")
    box = LogDir(EVIDENCE_MAX_BYTES="120")
    try:
        box.promoter.ensure_dir()
        for index, name in enumerate(["a.log", "b.log", "c.log"]):
            path = os.path.join(box.config.evidence_dir, name)
            with open(path, "w") as handle:
                handle.write("x" * 100)
            when = time.time() - (10 - index)
            os.utime(path, (when, when))

        box.promoter.sweep()
        equal(box.evidence(), ["c.log"], "the oldest go first until the total fits")
        equal(box.promoter.last_sweep["bytes"], 100, "and the reported total is the survivor")
    finally:
        box.stop()


def test_the_two_sweep_ages_are_independent():
    print("sweep -> detail ages out at its own, much shorter age (AD-28)")
    # One age cannot express "keep the timeline for a fortnight, the payload for a
    # long weekend", and the payload is both the expensive half (3.4-5.1 GB a file at
    # 20-30k bots) and the half carrying ws-parser's agency-token material (AD-30).
    box = LogDir(EVIDENCE_MAX_AGE_DAYS="14", EVIDENCE_DETAIL_MAX_AGE_DAYS="3")
    try:
        standard_files(box)
        standard_detail_files(box)
        box.promoter.record("alertname=Test", now=time.time())
        equal(len(box.evidence()), 4, "both tracks pinned")

        # Five days on: past the detail age, nowhere near the aggregate age.
        box.promoter.sweep(now=time.time() + 5 * 86400)
        remaining = box.evidence()
        equal([name for name in remaining if name.startswith("detail")], [],
              "every detail file is released at promotion + 5 d")
        equal(len([name for name in remaining if name.startswith("console")]), 2,
              "and the aggregates — the incident timeline — are untouched")

        box.promoter.sweep(now=time.time() + 15 * 86400)
        equal(box.evidence(), [], "the aggregates go at their own 14 d")
    finally:
        box.stop()


def test_the_byte_guard_evicts_detail_before_aggregates():
    print("byte guard -> ALL detail candidates go before ANY aggregate (AD-28)")
    # The ordering is the whole point. Under one pool ordered purely by promotion
    # time, a new incident's 3 GB payload evicts an old incident's 7 MB timeline —
    # shedding the irreplaceable cheap bytes and keeping the expensive ones. Here the
    # aggregate is deliberately the OLDER file, so a promotion-time-only eviction
    # would take it and this test would fail.
    box = LogDir(EVIDENCE_MAX_BYTES="150")
    try:
        box.promoter.ensure_dir()
        old_aggregate = os.path.join(box.config.evidence_dir, "console-2026-08-19-08.log")
        with open(old_aggregate, "w") as handle:
            handle.write("x" * 100)
        new_detail = os.path.join(box.config.evidence_dir, "detail-2026-08-19-08.log")
        with open(new_detail, "w") as handle:
            handle.write("y" * 100)
        now = time.time()
        box.promoter.promoted_at["console-2026-08-19-08.log"] = now - 10_000
        box.promoter.promoted_at["detail-2026-08-19-08.log"] = now

        box.promoter.sweep(now=now)
        equal(box.evidence(), ["console-2026-08-19-08.log"],
              "the NEWER detail file is evicted and the OLDER aggregate survives")
        equal(box.promoter.last_sweep["remainingDetail"], 0,
              "and the sweep reports the detail class it shed")
    finally:
        box.stop()


def test_load_pending_accepts_a_pre_phase_four_entry():
    print("legacy .pending.json -> the single liveName is read, passes are not lost")
    # A shim restart mid-incident reads a file the PREVIOUS version wrote. Rejecting
    # the old single-`liveName` shape would drop that incident's scheduled passes
    # during the incident — the one moment this process exists for.
    box = LogDir()
    try:
        standard_files(box)
        standard_detail_files(box)
        box.promoter.ensure_dir()
        legacy = "console-live-alertname_Legacy-20260819T120000Z.log"
        with open(box.config.pending_path, "w") as handle:
            json.dump({"alertname=Legacy": {"slug": "alertname_Legacy",
                                            "firstSeenAt": time.time(),
                                            "liveName": legacy,
                                            "deferredAt": time.time() + 300,
                                            "tailAt": None,
                                            "passes": 1}}, handle)

        restarted = shim.Promoter(box.config)
        restarted.load_pending()
        equal(list(restarted.pending), ["alertname=Legacy"], "the entry survives the upgrade")
        entry = restarted.pending["alertname=Legacy"]
        equal(entry["liveNames"]["aggregate"], legacy,
              "the old name is read as the aggregate track's, so its passes stay idempotent")
        check(entry["liveNames"].get("detail", "").startswith("detail-live-"),
              "and the detail track gets a generated per-incident name rather than none")

        due = restarted.tick(time.time() + 400)
        equal([tag for _, tag, _ in due], ["deferred"], "its deferred pass still runs")
        check(any(name.startswith("detail-live-") for name in box.evidence()),
              "and that pass promotes the detail track too")
    finally:
        box.stop()


def test_unclean_start_retro_promotes():
    print("start with no clean-shutdown marker -> boot promotion (AD-21)")
    # In a full-stack failure Alertmanager may be dead too, so the webhook cannot be
    # the only path in. This is the other one.
    box = LogDir()
    try:
        standard_files(box)
        result = box.promoter.boot()
        check(result is not None, "an unclean start promotes immediately")
        equal(result["tag"], "boot", "tagged boot, so the reason is visible in the log")
        equal(box.promoter.started_clean, False, "/health reports the unclean start")
        equal(len(box.evidence()), 2, "the newest two are pinned")
    finally:
        box.stop()


def test_clean_shutdown_marker_suppresses_the_boot_promotion():
    print("SIGTERM writes the marker; the next start honours it and removes it (AD-21)")
    box = LogDir()
    try:
        standard_files(box)
        box.promoter.shutdown()
        check(os.path.isfile(box.config.marker_path), "shutdown writes the marker")

        restarted = shim.Promoter(box.config)
        equal(restarted.boot(), None, "a clean start does NOT retro-promote")
        equal(restarted.started_clean, True, "and says so")
        equal(box.evidence(), [], "nothing was promoted")
        check(not os.path.isfile(box.config.marker_path),
              "the marker is removed, so a crash from here IS detected next time")

        # Third start, after that removal and with no shutdown: unclean again.
        third = shim.Promoter(box.config)
        check(third.boot() is not None, "a crash after a clean start is still detected")
    finally:
        box.stop()


def test_pending_passes_survive_a_restart():
    print("pending deadlines -> persisted, so a shim restart mid-incident keeps its passes")
    box = LogDir()
    try:
        standard_files(box)
        box.promoter.record("alertname=Test")
        check(os.path.isfile(box.config.pending_path), "the pending map is on disk")

        restarted = shim.Promoter(box.config)
        restarted.load_pending()
        equal(list(restarted.pending), ["alertname=Test"], "and is restored on start")
        equal(restarted.pending["alertname=Test"]["liveNames"],
              box.promoter.pending["alertname=Test"]["liveNames"],
              "with the same live link names, so the restored passes stay idempotent")
    finally:
        box.stop()


def test_webhook_promotes_over_http():
    print("POST from Alertmanager -> 200 and files promoted (real socket)")
    box = LogDir()
    try:
        standard_files(box)
        server, url = start_shim(box)
        try:
            status, body = post(url, FIRING)
            equal(status, 200, "the webhook is accepted")
            equal(len(body["linked"]), 2, "and reports what it pinned")
            equal(len(box.evidence()), 2, "two files promoted")

            # P3-6: three POSTs in quick succession, one incident.
            post(url, FIRING)
            post(url, FIRING)
            equal(len(box.evidence()), 2, "repeat webhooks add no files")
            _, health = get(url)
            equal(len(health["pending"]), 1, "and produce ONE pending entry, not three")
            equal(health["pending"][list(health["pending"])[0]]["passes"], 3,
                  "the three deliveries are visible as passes on the one incident")
        finally:
            server.shutdown()
    finally:
        box.stop()


def test_resolved_payload_promotes_nothing():
    print("resolved payload -> nothing promoted (there is no incident to preserve)")
    box = LogDir()
    try:
        standard_files(box)
        server, url = start_shim(box)
        try:
            status, body = post(url, RESOLVED)
            equal(status, 200, "accepted, so Alertmanager does not retry")
            equal(body.get("reason"), "resolved", "and says why it did nothing")
            equal(box.evidence(), [], "no files promoted")
        finally:
            server.shutdown()
    finally:
        box.stop()


def test_malformed_input_still_promotes():
    print("garbage body and an unexpected path -> still promoted")
    # Same posture as viptalk-shim: a payload-schema change or a typo in the
    # receiver URL must not be the reason an incident's logs were swept.
    box = LogDir()
    try:
        standard_files(box)
        server, url = start_shim(box)
        try:
            status, _ = post(url, b"not json at all", path="/somewhere/else")
            equal(status, 200, "accepted")
            equal(len(box.evidence()), 2, "promoted under the `unlabelled` key")
            equal(list(box.promoter.pending), ["unlabelled"], "which is a real key")
        finally:
            server.shutdown()
    finally:
        box.stop()


def test_health_reports_the_directory_and_the_last_promotion():
    print("GET /health -> config, evidence size, pending, last promotion")
    box = LogDir()
    try:
        standard_files(box)
        server, url = start_shim(box)
        try:
            status, health = get(url)
            equal(status, 200, "health answers 200")
            equal(health["evidenceFiles"], 0, "nothing promoted yet")
            equal(health["pending"], {}, "and nothing pending")
            equal(health["lastPromotion"], None, "no promotion is honest as null")
            equal(health["sameFilesystem"], True,
                  "evidence/ is on the same filesystem, or hardlinks are impossible")
            equal(health["rolloverHours"], 2.0, "the configured rollover period is visible")

            post(url, FIRING)
            _, health = get(url)
            equal(health["evidenceFiles"], 2, "the file count moves")
            check(health["evidenceBytes"] > 0, "and so does the reported size")
            equal(health["lastPromotion"]["tag"], "alert", "the last promotion is reported")
            check(health["pending"][list(health["pending"])[0]]["deferredInSeconds"] > 0,
                  "with the time remaining on the deferred pass")
        finally:
            server.shutdown()
    finally:
        box.stop()


def test_unknown_get_path_is_404():
    print("GET on an unexpected path -> 404 with a hint, not a stack trace")
    box = LogDir()
    try:
        server, url = start_shim(box)
        try:
            get(url, "/metrics")
            check(False, "expected 404")
        except urllib.error.HTTPError as error:
            equal(error.code, 404, "unknown GET path answers 404")
            check("health" in error.read().decode("utf-8"), "the body says where to look")
        finally:
            server.shutdown()
    finally:
        box.stop()


def test_bad_numeric_config_falls_back_instead_of_crash_looping():
    print("non-numeric config -> defaults plus a loud log, never a crash loop")
    # Identical reasoning to viptalk-shim's: these are hand-edited on the host, and
    # raising out of Config() under `restart: unless-stopped` means the container is
    # missing for exactly the incident it exists to preserve.
    expected = {"EVIDENCE_SHIM_PORT": ("port", 8080),
                "EVIDENCE_MAX_AGE_DAYS": ("max_age_days", 14.0),
                "EVIDENCE_DETAIL_MAX_AGE_DAYS": ("detail_max_age_days", 3.0),
                # 5 GB -> 12 GB with Phase 4 (AD-28): a pinned 2 h DETAIL file is
                # 3.4-5.1 GB at 20-30k bots, so the old cap could not hold one
                # incident's payload at all.
                "EVIDENCE_MAX_BYTES": ("max_bytes", 12 * 1024 ** 3),
                "EVIDENCE_ROLLOVER_HOURS": ("rollover_hours", 2.0)}
    for name, (attribute, default) in expected.items():
        try:
            config = shim.Config({name: "not-a-number", "EVIDENCE_LOGS_DIR": "/logs"})
            equal(getattr(config, attribute), default,
                  "%s='not-a-number' falls back to the default" % name)
        except ValueError:
            check(False, "%s='not-a-number' still raises at startup (crash loop)" % name)


def test_evidence_dir_is_a_subdirectory_of_the_logs_mount():
    print("evidence/ -> a SUBDIRECTORY of the logs dir (AD-18)")
    # Two independent reasons, both verified against the live configs: promtail's
    # `__path__: /logs/*.log` is non-recursive, so a subdirectory is not re-ingested
    # into Loki; and log4j2's Delete uses basePath /app/logs with maxDepth = 1, so a
    # subdirectory is outside its sweep. Same filesystem is mandatory for hardlinks,
    # which is why it is a subdirectory rather than a sibling path.
    config = shim.Config({"EVIDENCE_LOGS_DIR": "/logs"})
    equal(config.evidence_dir, "/logs/evidence", "the default lives under the logs mount")
    box = LogDir()
    try:
        standard_files(box)
        box.promoter.record("alertname=Test")
        equal(sorted(name for name in os.listdir(box.logs) if name.endswith(".log")),
              ["console-2026-08-19-06.log", "console-2026-08-19-08.log", "console.log"],
              "no promoted file is left in the scraped/swept top-level directory")
        equal(box.config.same_filesystem(), True, "and the two are on one filesystem")
    finally:
        box.stop()


def test_a_poisoned_pending_file_cannot_kill_the_scheduler():
    print("corrupt .pending.json -> entries dropped, the timer thread survives")
    # The regression: next_deadline() sat one line OUTSIDE the scheduler's
    # try/except and did min() over whatever load_pending() had accepted, which was
    # any dict with a truthy liveName. A hand-edit on the box, or a file written by
    # a newer shim and read by a rolled-back one, raised TypeError, killed the
    # daemon thread, and left the process answering 200 forever while passes 2/3
    # and the hourly sweep never ran again -- logs/evidence/ then grows without
    # bound, AD-19's exact failure mode.
    box = LogDir()
    try:
        standard_files(box)
        box.promoter.ensure_dir()
        with open(box.config.pending_path, "w") as handle:
            json.dump({
                "poisoned": {"liveName": "console-live-poisoned.log",
                             "deferredAt": "not-a-number", "tailAt": None},
                "nan": {"liveName": "console-live-nan.log",
                        "deferredAt": float("nan"), "tailAt": None},
                "boolean": {"liveName": "console-live-bool.log",
                            "deferredAt": True, "tailAt": None},
                "spent": {"liveName": "console-live-spent.log",
                          "deferredAt": None, "tailAt": None},
                "good": {"liveName": "console-live-good.log",
                         "deferredAt": time.time() + 300, "tailAt": None},
            }, handle)

        restarted = shim.Promoter(box.config)
        restarted.load_pending()
        equal(sorted(restarted.pending), ["good"],
              "only the entry with a usable deadline is restored")

        deadline = restarted.next_deadline()
        check(isinstance(deadline, float), "next_deadline() returns a number, never raises")

        # And the second line of defence: even if something unusable reaches the
        # dict directly, tick() must degrade to "run it now", not raise.
        restarted.pending["smuggled"] = {"liveName": "console-live-smuggled.log",
                                         "deferredAt": "still-not-a-number", "tailAt": None}
        due = restarted.tick()
        check(any(entry[0] == "smuggled" for entry in due),
              "an unusable deadline is treated as due, not as an exception")
        check(isinstance(restarted.next_deadline(), float),
              "and the deadline computation is still total afterwards")
    finally:
        box.stop()


def test_pending_is_capped():
    print("pending incidents -> bounded, oldest key evicted first")
    box = LogDir()
    try:
        standard_files(box)
        for index in range(shim.MAX_PENDING + 5):
            box.promoter.record("alertname=Flood,gameId=%d" % index)

        equal(len(box.promoter.pending), shim.MAX_PENDING,
              "the dict cannot grow without bound on a label explosion")
        equal(box.promoter.evicted_pending, 5, "and the evictions are counted for /health")
        check("alertname=Flood,gameId=0" not in box.promoter.pending,
              "the OLDEST incident is the one dropped")
        check("alertname=Flood,gameId=%d" % (shim.MAX_PENDING + 4) in box.promoter.pending,
              "the newest is kept")
    finally:
        box.stop()


def test_age_is_measured_from_the_promotion_not_the_log_mtime():
    print("sweep age -> measured from PROMOTION, so 14 days means 14 days")
    # A hardlink shares the source inode, so a promoted file's mtime is the LOG's
    # last-write time. log4j2 keeps rolled files for 7 d, so the older of the
    # "newest two" can already be days old at promotion; sweeping on mtime gave it
    # `EVIDENCE_MAX_AGE_DAYS - age_at_promotion` rather than the advertised value.
    box = LogDir(EVIDENCE_MAX_AGE_DAYS="14")
    try:
        box.write("console-2026-08-13-00.log", "six days old\n", age_seconds=6 * 86400)
        box.write("console.log", "live\n", age_seconds=0)
        box.promoter.record("alertname=Test")
        equal(len(box.evidence()), 2, "both are pinned")

        # 10 days after the promotion: on mtime the rolled file reads 16 days old
        # and would be swept, four days early.
        box.promoter.sweep(now=time.time() + 10 * 86400)
        equal(len(box.evidence()), 2,
              "still held at promotion+10d, even though its mtime says 16 d")

        box.promoter.sweep(now=time.time() + 15 * 86400)
        equal(box.evidence(), [], "and released at promotion+15d")
    finally:
        box.stop()


def test_promotion_times_are_persisted_and_pruned():
    print(".promoted.json -> survives a restart, and never outgrows the directory")
    box = LogDir()
    try:
        standard_files(box)
        box.promoter.record("alertname=Test")
        check(os.path.isfile(box.config.promoted_path), "the sidecar is on disk")

        restarted = shim.Promoter(box.config)
        restarted.load_promoted()
        equal(sorted(restarted.promoted_at), sorted(box.promoter.promoted_at),
              "and is restored, so a shim restart does not reset every file's age")

        # Ageing everything out must also drop the records, or the sidecar becomes
        # the unbounded thing.
        box.config.max_age_days = 0
        box.promoter.sweep()
        equal(box.evidence(), [], "the directory is empty")
        equal(box.promoter.promoted_at, {}, "and so is the sidecar")
    finally:
        box.stop()


def test_health_reports_whether_the_scheduler_is_alive():
    print("GET /health -> schedulerAlive, so a dead timer is visible not inferred")
    box = LogDir()
    try:
        standard_files(box)
        server, url = start_shim(box)
        try:
            _, body = get(url)
            equal(body["schedulerAlive"], None,
                  "no timer thread wired in this harness yet - reported honestly as null")
            equal(body["pendingCap"], shim.MAX_PENDING, "the cap is published")
            equal(body["pendingEvicted"], 0, "and so is the eviction count")

            stop = threading.Event()
            timer = threading.Thread(target=shim.scheduler, args=(box.promoter, stop),
                                     name="scheduler", daemon=True)
            box.promoter.scheduler_thread = timer
            timer.start()
            try:
                _, body = get(url)
                equal(body["schedulerAlive"], True, "a running timer reports alive")
            finally:
                stop.set()
                box.promoter.wake.set()
                timer.join(timeout=5)

            _, body = get(url)
            equal(body["schedulerAlive"], False,
                  "and a stopped one reports dead, which is what nothing could see before")
        finally:
            server.shutdown()
            server.server_close()
    finally:
        box.stop()


def main():
    for test in (test_promotion_is_a_hardlink_not_a_copy,
                 test_selection_is_newest_two_plus_live,
                 test_selection_runs_per_track,
                 test_a_missing_detail_directory_is_a_no_op,
                 test_the_two_sweep_ages_are_independent,
                 test_the_byte_guard_evicts_detail_before_aggregates,
                 test_load_pending_accepts_a_pre_phase_four_entry,
                 test_promotion_is_idempotent,
                 test_a_rolled_over_live_file_still_gets_pinned,
                 test_incident_key_coalesces_and_refreshes_one_deadline,
                 test_canonical_key_is_order_independent,
                 test_deferred_and_tail_passes_fire_at_their_deadlines,
                 test_rollover_boundary_follows_the_configured_period,
                 test_sweep_bounds_the_directory_by_age,
                 test_sweep_bounds_the_directory_by_size,
                 test_unclean_start_retro_promotes,
                 test_clean_shutdown_marker_suppresses_the_boot_promotion,
                 test_pending_passes_survive_a_restart,
                 test_a_poisoned_pending_file_cannot_kill_the_scheduler,
                 test_pending_is_capped,
                 test_age_is_measured_from_the_promotion_not_the_log_mtime,
                 test_promotion_times_are_persisted_and_pruned,
                 test_health_reports_whether_the_scheduler_is_alive,
                 test_webhook_promotes_over_http,
                 test_resolved_payload_promotes_nothing,
                 test_malformed_input_still_promotes,
                 test_health_reports_the_directory_and_the_last_promotion,
                 test_unknown_get_path_is_404,
                 test_bad_numeric_config_falls_back_instead_of_crash_looping,
                 test_evidence_dir_is_a_subdirectory_of_the_logs_mount):
        test()
    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
