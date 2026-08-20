#!/usr/bin/env python3
"""Out-of-band retention promotion: pin the log files around an incident.

WHY THIS EXISTS (LOG_VOLUME_TIERING Phase 3, AD-13 .. AD-21)
------------------------------------------------------------
Phase 0 made retention cheap and Phase 1 made the default level cheap. Cheap
retention has exactly one failure mode: the window that mattered ages out before
anyone reads it. log4j2's `Delete` sweeps `console-*.log` on age OR accumulated
size, Loki gives DEBUG 24 h, and neither of them knows an incident happened.

This process is what knows. Alertmanager webhooks it when something bad fires;
it HARDLINKS the newest log files into `logs/evidence/`, which is outside both
sweepers, so the incident's logs survive while everything else keeps ageing out
fast. That is the whole feature: a cheap default retention that never costs us
the one window that mattered.

WHY A SEPARATE CONTAINER (AD-13)
--------------------------------
bot-manager cannot promote its own logs when bot-manager is the thing that died,
which is the identical reasoning that already justifies `viptalk-shim` for
`BotManagerDown` — see docker-compose.yml and viptalk-shim/shim.py, on which this
file is directly modelled. It shares no code, no Mongo and no JVM with the app,
it deliberately has NO `depends_on: bot-manager`, and it is a stock
`python:3.12-alpine` with this script bind-mounted (stdlib only, no build step —
`deploy.sh` only runs `docker compose up`). It runs as the same
`${HOST_UID}:${HOST_GID}` as bot-manager so it can write into the bind-mounted
`logs/`; `os.link` needs write+execute on the DESTINATION directory and nothing
else, so there is no privilege here and none is available (no sudo on Bot-1).

`ln`, NEVER `cp` (AD-14)
------------------------
A copy doubles the bytes at exactly the moment disk is the constraint — and disk
is what killed this box on 2026-06-30. A hardlink costs zero additional blocks
and still defeats `Delete`: unlinking the rolled file removes a directory entry,
not the inode, and the inode stays alive while our link holds a reference. The
LIVE `console.log` is hardlinked too: our link follows the ORIGINAL inode, so it
keeps growing until log4j2 renames that file at rollover and creates a fresh
one — which captures precisely the post-incident tail and then stops. That is by
construction, not luck.

TWO TRACKS (Phase 4, AD-28)
---------------------------
Phase 4 split the application's output in two, and this process promotes BOTH:

  aggregate  /logs/console.log + /logs/console-*.log — JSON, INFO+ only, the
             only track Loki ingests. Tiny. It is the incident TIMELINE: the
             fleet rollup, the group lifecycle lines, the scoped-debug
             escalation record.
  detail     /logs/detail/detail.log + /logs/detail/detail-*.log — PatternLayout,
             the ws-parser library in full plus every DEBUG/TRACE line, never in
             Loki, swept locally at 12 h or 10 GB. It is what forensics actually
             reads line by line.

Neither is sufficient alone: without the aggregate you have no timeline, without
the detail you have no per-bot behaviour. The aggregate costs nothing to pin.
The detail does NOT — a pinned 2 h detail file is 3.4–5.1 GB at 20–30k bots and
those blocks cannot be reclaimed until the last name is unlinked, which is why
the byte guard evicts detail files before aggregate ones and why they get their
own, much shorter age (`EVIDENCE_DETAIL_MAX_AGE_DAYS`, 3 d against the
aggregate's 14). It is also why 3 days is the right number for a second reason:
the ws-parser library logs agency-token material at INFO, which after Phase 4
exists only in the detail track (AD-30).

A MISSING DETAIL DIRECTORY IS A NO-OP, NOT AN ERROR. That is deliberate: this
file can ship before the log4j2 change that creates `/logs/detail/`, and until
then it behaves exactly as it did before.

NEWEST TWO AT EXECUTION TIME, PER TRACK (AD-15)
-----------------------------------------------
Files are chosen by mtime at the moment a pass runs, never by arithmetic on the
alert's timestamp. This re-centres the window automatically: an incident at
minute 118 of a 2 h period yields a previous file holding 58 min of normal
operation plus 2 min of the incident, and a live file holding the rest, with the
ancient period falling off by itself. Timestamp arithmetic would need clock-skew
handling and would still pick the wrong file at a rollover boundary.

The rule runs independently per track, so a pass pins six names and at most
three inodes per track. Both tracks roll on the SAME log4j2 boundary (AD-22),
which is what lets one tail pass close both live files — no second timer.

THREE PASSES, ALL THE SAME OPERATION (AD-16)
--------------------------------------------
  pass 1  immediately on the webhook — insurance. `BotManagerDown` fires while
          the box may still be going down; waiting even 5 minutes loses the worst
          incident class.
  pass 2  at +5 min, matching Alertmanager's `group_interval`.
  pass 3  at the next rollover boundary + 120 s, so the file that was LIVE at
          T+0 is also pinned under its rolled name once it has closed.
Every pass is the same idempotent "newest-two-plus-live" operation, so repeats
cost nothing and a mistimed pass 3 is harmless — the live inode was already
pinned by pass 1, so pass 3 only ever adds a second name for it.

The rollover period is CONFIGURED, not assumed: log4j2's interval is 2 h as of
Phase 0 (AD-20) and `EVIDENCE_ROLLOVER_HOURS` must track it. Boundaries are
computed in this container's local time because `modulate = true` aligns them in
the JVM's local time, and both containers inherit the host's (UTC by default).

ONE DEADLINE PER INCIDENT KEY (AD-17)
-------------------------------------
The incident key is the webhook's `groupLabels` rendered canonically — exactly
the tuple Alertmanager already dedups on (`alertname, product, environmentId,
audience`). Alerts carry `product` / `environmentId` / `gameId` but NOT
`botGroupId` (`groups_dead_by_env` has no group-id label), so a per-group
deadline is not expressible from the payload and is not attempted. A
deteriorating environment redelivering every 5 minutes REFRESHES its one pair of
deadlines; it never stacks timers.

BEHAVIOUR
---------
  POST <any path>   Alertmanager webhook. Runs pass 1 and schedules 2 and 3.
                    200 when the pass ran; 502 only on an unexpected failure, so
                    Alertmanager's retry stays meaningful. A payload marked
                    `resolved` is ignored (nothing to preserve on recovery).
  GET  /health      Resolved config, pending incidents, the outcome of the last
                    promotion, the evidence directory's file count and size, and
                    `schedulerAlive` — the timer thread runs passes 2 and 3 and the
                    hourly sweep, and its death is otherwise invisible because HTTP
                    keeps answering 200. Never touches bot-manager, never touches
                    the network.

Deliberately permissive about the request path and the body shape, for the same
reason viptalk-shim is: a typo in a receiver URL or an Alertmanager schema change
must not be the reason an incident's logs were swept.

Self-test: `python3 evidence-shim/selftest.py` (no network, no containers).
"""

import collections
import fnmatch
import json
import os
import signal
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# One entry per output track (Phase 4 / AD-28). `glob` covers a track's live file
# AND its rolled siblings; `live_template` is the per-incident name the live file
# is linked under, stamped with (slug, first-seen UTC timestamp). Kept in step
# with logging/log4j2.properties -- if either filePattern changes, this changes.
Track = collections.namedtuple("Track", "name directory glob live_name live_template")

AGGREGATE = "aggregate"
DETAIL = "detail"

# One set of blocks in evidence/, with every name that points at it. Evidence
# entries are hardlinks and one inode routinely carries two names (AD-16: pass 1
# pins the live file, pass 3 pins the same inode under its rolled name), so the
# byte guard has to count inodes, not directory entries -- see `inode_groups()`.
InodeGroup = collections.namedtuple("InodeGroup", "promoted mtime size detail paths")

# The per-incident name each track's LIVE file is linked under. One source of
# truth: `record()` stamps them onto a new incident, and `load_pending` fills
# them in for an entry written by a shim that only knew about one track.
LIVE_TEMPLATES = {
    AGGREGATE: "console-live-%s-%s.log",
    DETAIL: "detail-live-%s-%s.log",
}

# How an evidence file is classified back into its track during the sweep. Both
# `detail-<date>.log` (rolled) and `detail-live-<slug>-<ts>.log` (promoted live)
# start with it; every aggregate name starts with `console`. Filename prefix
# rather than a sidecar map on purpose: the classification must still be right
# for a file promoted by an older shim, or by a hand-run `ln`.
DETAIL_PREFIX = "detail"

# Written on SIGTERM, removed on start. Its ABSENCE at start is what tells us the
# previous run died rather than stopped (AD-21).
CLEAN_MARKER = ".clean-shutdown"
# Scheduled passes survive a restart of this container, so a shim redeploy in the
# middle of an incident does not silently drop pass 2 and pass 3.
PENDING_FILE = ".pending.json"
# basename -> epoch seconds at which we hardlinked it. A hardlink SHARES the
# source inode, so a promoted file's mtime is the log's last-write time, not the
# promotion time: the older of the "newest two" can already be hours or days old
# when it is pinned, and sweeping on mtime would give it
# `EVIDENCE_MAX_AGE_DAYS - age_at_promotion` rather than the advertised
# EVIDENCE_MAX_AGE_DAYS. This sidecar is what makes the advertised number true.
PROMOTED_FILE = ".promoted.json"

# How many files "newest two" means (AD-15). Not an env var in docker-compose.yml
# on purpose: it is a design constant of the window, not an operational knob.
DEFAULT_NEWEST_COUNT = 2

# Anti-leak ceiling on in-flight incident keys. Every Java map this feature added
# carries one (ScopedDebugRegistry 50, GroupLifecycleAggregator 2000,
# ScopedDebugEscalator 2000); `pending` was the one collection without. Each entry
# costs an O(n) rewrite of .pending.json per webhook and a distinct
# console-live-<slug>-<ts>.log inode, and the AD-19 sweep bounds BYTES, not inodes
# or dict size -- so a label explosion inside the compose network (say `gameId`
# leaking into Alertmanager's group_by) is a plausible way to mint thousands of
# both. Alertmanager's own grouping keeps this in single digits normally.
MAX_PENDING = 64


def _eviction_order(group):
    """The order the byte guard sheds evidence in: oldest promotion first.

    Explicit, and explicitly three-deep, because the tie-break is where this went
    wrong. `remember_promoted` stamps ONE `time.time()` per pass, so every file a
    pass pins carries an identical promotion time and the tie-break is not an edge
    case -- it decides the ordinary case. Sorting the bare tuple made it fall
    through to `size` ascending, which picks the SMALLEST file of the pass: normally
    the live-file link, i.e. the post-incident tail, the one file pass 3 exists to
    capture and the only one that cannot be reconstructed once log4j2 has swept its
    rolled sibling.

      1. `promoted` -- AD-28's stated rule, "oldest-promotion first within each
         class". Unchanged.
      2. `mtime` -- the log's own last-write time, which for equal promotion stamps
         orders the closed archives (last written before the boundary) ahead of the
         still-growing live file. Content age, not file size.
      3. `paths[0]` -- unique per group, so the order is total and two runs over the
         same directory always evict the same file.
    """
    return (group.promoted, group.mtime, group.paths[0])


def _env(name, default=""):
    value = os.environ.get(name)
    return default if value is None else value.strip()


def _number(name, raw, default, cast):
    """A numeric env var, falling back LOUDLY to `default` on anything unparseable.

    Same posture as viptalk-shim: these values are hand-edited on the host, so a
    typo is likely, and raising out of Config() before the server binds would be a
    crash loop under `restart: unless-stopped` -- i.e. the shim absent during
    exactly the incident it exists to preserve.
    """
    if not raw:
        return default
    try:
        return cast(raw)
    except (TypeError, ValueError):
        log("WARN %s=%r is not a number - falling back to %r" % (name, raw, default))
        return default


class Config(object):
    """Everything this process knows, resolved once at startup from the environment."""

    def __init__(self, environ=None):
        get = (lambda name, default="": _env(name, default)) if environ is None \
            else (lambda name, default="": (environ.get(name) or default).strip())

        # Defaults match the compose bind mount (`./logs:/logs`, read-WRITE unlike
        # promtail's). Both must stay on ONE filesystem: hardlinks cannot cross
        # devices, and a cross-device evidence dir would fail every promotion
        # (reported by `sameFilesystem` on /health rather than discovered later).
        self.logs_dir = get("EVIDENCE_LOGS_DIR", "/logs")
        self.evidence_dir = get("EVIDENCE_DIR") or os.path.join(self.logs_dir, "evidence")
        # Track 2's directory (Phase 4 / AD-25). A SUBDIRECTORY of logs_dir, which is
        # what keeps it out of Loki -- promtail's __path__: /logs/*.log is
        # non-recursive. It is created by log4j2's FileManager, not by this process:
        # if it is absent, this shim treats the track as empty rather than
        # manufacturing a directory the app is supposed to own.
        self.detail_dir = get("EVIDENCE_DETAIL_DIR") or os.path.join(self.logs_dir, DETAIL)
        self.port = _number("EVIDENCE_SHIM_PORT", get("EVIDENCE_SHIM_PORT", "8080"), 8080, int)

        # AD-19: evidence/ escapes both sweepers, so it needs its own or the fix for
        # unbounded growth is itself unbounded growth. Age first, then oldest-first
        # until under the size guard. 0 days is a VALID setting meaning "sweep
        # everything" (it is how the guard is verified), not "disabled".
        self.max_age_days = _number("EVIDENCE_MAX_AGE_DAYS",
                                    get("EVIDENCE_MAX_AGE_DAYS", "14"), 14.0, float)
        # Detail files get their own, much shorter age (AD-28). Two reasons, both
        # sufficient: a pinned 2 h detail file is 3.4-5.1 GB at 20-30k bots against a
        # few MB for the aggregate it accompanies, and the ws-parser library logs
        # agency-token material at INFO, which after Phase 4 lives only in this track
        # (AD-30). Independent of max_age_days on purpose -- one age cannot express
        # "keep the timeline for a fortnight, the payload for a long weekend".
        self.detail_max_age_days = _number("EVIDENCE_DETAIL_MAX_AGE_DAYS",
                                           get("EVIDENCE_DETAIL_MAX_AGE_DAYS", "3"), 3.0, float)
        self.max_bytes = _number("EVIDENCE_MAX_BYTES",
                                 get("EVIDENCE_MAX_BYTES", str(12 * 1024 ** 3)),
                                 12 * 1024 ** 3, int)
        self.sweep_interval = _number("EVIDENCE_SWEEP_INTERVAL_SECONDS",
                                      get("EVIDENCE_SWEEP_INTERVAL_SECONDS", "3600"),
                                      3600.0, float)

        # MUST track appender.rolling.policies.time.interval in
        # logging/log4j2.properties (2 h since Phase 0 / AD-20). Deriving pass 3
        # from a hardcoded hour would put it on the wrong side of the boundary.
        self.rollover_hours = _number("EVIDENCE_ROLLOVER_HOURS",
                                      get("EVIDENCE_ROLLOVER_HOURS", "2"), 2.0, float)
        if self.rollover_hours <= 0:
            log("WARN EVIDENCE_ROLLOVER_HOURS=%r is not positive - using 2"
                % (self.rollover_hours,))
            self.rollover_hours = 2.0

        # Pass 2 at +5 min matches Alertmanager's group_interval; pass 3 at the
        # boundary + 120 s leaves the rename comfortably finished (AD-16).
        self.deferred_delay = _number("EVIDENCE_DEFERRED_DELAY_SECONDS",
                                      get("EVIDENCE_DEFERRED_DELAY_SECONDS", "300"),
                                      300.0, float)
        self.tail_delay = _number("EVIDENCE_TAIL_DELAY_SECONDS",
                                  get("EVIDENCE_TAIL_DELAY_SECONDS", "120"), 120.0, float)
        self.newest_count = _number("EVIDENCE_NEWEST_COUNT",
                                    get("EVIDENCE_NEWEST_COUNT", str(DEFAULT_NEWEST_COUNT)),
                                    DEFAULT_NEWEST_COUNT, int)

    def tracks(self):
        """The output tracks to promote, in the order they are reported (AD-28).

        Resolved from config rather than being module constants because the detail
        directory is overridable and because a track is (directory, glob, live name)
        together -- splitting them was how the pre-Phase-4 shim ended up hardcoded to
        one track without anything noticing.
        """
        return (
            Track(AGGREGATE, self.logs_dir, "console*.log", "console.log",
                  LIVE_TEMPLATES[AGGREGATE]),
            Track(DETAIL, self.detail_dir, "detail*.log", "detail.log",
                  LIVE_TEMPLATES[DETAIL]),
        )

    @property
    def marker_path(self):
        return os.path.join(self.evidence_dir, CLEAN_MARKER)

    @property
    def pending_path(self):
        return os.path.join(self.evidence_dir, PENDING_FILE)

    @property
    def promoted_path(self):
        return os.path.join(self.evidence_dir, PROMOTED_FILE)

    def same_filesystem(self):
        """True iff a hardlink from logs_dir into evidence_dir can even be attempted.

        A cross-device destination makes EVERY promotion fail with EXDEV. Checked
        up front and published on /health so the answer is visible before an
        incident rather than in the traceback of the pass that mattered.
        """
        try:
            return os.stat(self.logs_dir).st_dev == os.stat(self.evidence_dir).st_dev
        except OSError:
            return None

    def summary(self):
        return {
            "logsDir": self.logs_dir,
            # Published so "the shim is not seeing track 2" is answerable from
            # /health rather than by reasoning about a zero file count (P4-9).
            "detailDir": self.detail_dir,
            "detailDirPresent": os.path.isdir(self.detail_dir),
            "evidenceDir": self.evidence_dir,
            "sameFilesystem": self.same_filesystem(),
            "maxAgeDays": self.max_age_days,
            "detailMaxAgeDays": self.detail_max_age_days,
            "maxBytes": self.max_bytes,
            "sweepIntervalSeconds": self.sweep_interval,
            "rolloverHours": self.rollover_hours,
            "deferredDelaySeconds": self.deferred_delay,
            "tailDelaySeconds": self.tail_delay,
            "newestCount": self.newest_count,
        }


def log(message):
    sys.stderr.write(time.strftime("%Y-%m-%d %H:%M:%S ") + message + "\n")
    sys.stderr.flush()


def canonical_key(group_labels):
    """Alertmanager's `groupLabels` rendered canonically -- the incident key (AD-17).

    Sorted so label order in the payload cannot produce two keys for one incident.
    An empty or missing map still yields a key: an unlabelled incident is still an
    incident, and dropping it would mean not promoting anything.
    """
    if not isinstance(group_labels, dict) or not group_labels:
        return "unlabelled"
    return ",".join("%s=%s" % (name, "" if group_labels[name] is None else group_labels[name])
                    for name in sorted(group_labels))


def slug(key):
    """The incident key as a filename fragment: predictable, bounded, no separators."""
    safe = "".join(character if (character.isalnum() or character in "._-") else "_"
                   for character in key)
    while "__" in safe:
        safe = safe.replace("__", "_")
    return safe.strip("_")[:96] or "incident"


def _deadline(value):
    """A persisted deadline coerced to float, or None if it is not usable.

    `None` is legitimate (that pass has already run). Anything else -- a string, a
    bool, a dict -- is rejected rather than carried, because it reaches `min()` in
    next_deadline() and `>=` in tick(), both of which raise TypeError on it.
    """
    if value is None or isinstance(value, bool):
        return None
    try:
        deadline = float(value)
    except (TypeError, ValueError):
        return None
    return deadline if deadline == deadline else None  # NaN poisons min()/comparisons


def live_names_for(slug_value, when):
    """The live-link name for every track, stamped from FIRST sight of the incident.

    Stable across the incident's three passes, which is what makes the repeats of
    AD-16 free: passes 2 and 3 hit FileExistsError on the same names and skip.
    """
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(when))
    return {name: template % (slug_value, stamp)
            for name, template in LIVE_TEMPLATES.items()}


def _live_names(value):
    """Normalise a pending entry's live-link names to {track: filename}.

    Phase 4 turned one `liveName` string into a `liveNames` map, and a shim restart
    in the middle of an incident reads a file the PREVIOUS version wrote. Rejecting
    the old shape there would drop that incident's scheduled passes -- during the
    incident, which is the one moment this process exists for -- so the legacy
    string is accepted and read as the aggregate track's name.
    """
    if isinstance(value, dict):
        return {name: stored for name, stored in value.items()
                if isinstance(stored, str) and stored}
    if isinstance(value, str) and value:
        return {AGGREGATE: value}
    return {}


def _sane_entry(key, entry):
    """A restored pending entry, normalised — or None if it cannot be trusted."""
    if not isinstance(entry, dict):
        return None
    names = _live_names(entry.get("liveNames")
                        if entry.get("liveNames") is not None else entry.get("liveName"))
    if not names and not entry.get("slug"):
        return None
    deferred = _deadline(entry.get("deferredAt"))
    tail = _deadline(entry.get("tailAt"))
    if deferred is None and tail is None:
        return None  # both passes already run (or both unusable): nothing to schedule
    sane = dict(entry)
    sane["deferredAt"] = deferred
    sane["tailAt"] = tail
    first_seen = _deadline(entry.get("firstSeenAt"))
    sane["firstSeenAt"] = first_seen
    # Any track the stored entry does not name -- always the detail track for an
    # entry written before Phase 4 -- gets a name generated the same way record()
    # would have, so the restored passes pin it under a per-incident name rather
    # than under the reused source name.
    generated = live_names_for(entry.get("slug") or slug(key),
                               first_seen if first_seen is not None else time.time())
    for track_name, generated_name in generated.items():
        names.setdefault(track_name, generated_name)
    sane["liveNames"] = names
    sane.pop("liveName", None)
    return sane


def next_rollover(now, hours):
    """The next log4j2 rollover boundary at or after `now`, in LOCAL time.

    `modulate = true` aligns boundaries to the clock (even hours for a 2 h
    interval) in the JVM's default zone; this container and bot-manager's inherit
    the same host zone, so aligning to the local-time epoch matches. Being wrong
    about this is survivable, not fatal: pass 1 already hardlinked the live
    inode, so a mistimed pass 3 loses nothing (see AD-16 in the module docstring).
    """
    period = max(1.0, hours) * 3600.0
    offset = time.localtime(now).tm_gmtoff or 0
    local = now + offset
    return (int(local // period) + 1) * period - offset


class Promoter(object):
    """Selection, linking, scheduling and sweeping. All state lives here."""

    def __init__(self, config):
        self.config = config
        self.lock = threading.RLock()
        self.wake = threading.Event()
        # key -> {"slug", "liveNames", "firstSeenAt", "deferredAt", "tailAt", "passes"}
        self.pending = {}
        # basename -> epoch seconds we promoted it (see PROMOTED_FILE).
        self.promoted_at = {}
        self.last_promotion = None
        self.last_sweep = None
        self.next_sweep = time.time() + config.sweep_interval
        self.started_clean = None
        self.evicted_pending = 0
        self.scheduler_thread = None

    # ------------------------------------------------------------------ selection

    def candidates(self, track):
        """One track's files, newest first. Never recurses, never raises.

        A track whose directory does not exist yields nothing and says nothing: this
        file is safe to deploy before the log4j2 change that creates `/logs/detail/`,
        and on such a host it behaves exactly as the single-track shim did. Only a
        directory that EXISTS and cannot be listed is an error.
        """
        directory = track.directory
        try:
            names = os.listdir(directory)
        except OSError as error:
            if not os.path.isdir(directory):
                return []
            log("ERROR cannot list %s: %s" % (directory, error))
            return []
        found = []
        for name in names:
            if not fnmatch.fnmatch(name, track.glob):
                continue
            path = os.path.join(directory, name)
            try:
                stat = os.stat(path)
            except OSError:
                continue  # rolled away between listdir and stat; nothing to do
            if not os.path.isfile(path):
                continue
            found.append((stat.st_mtime, name, path))
        # Descending mtime, then descending name as the tiebreak -- "console.log"
        # sorts after "console-<date>.log", so on identical timestamps the LIVE
        # file is still treated as the newest, which is what it is.
        found.sort(key=lambda entry: (entry[0], entry[1]), reverse=True)
        return found

    def selection(self, track):
        """One track's newest N plus its live file (AD-15).

        Returns [(name, path, is_live)]. Run per track and unioned by the caller, so
        a pass pins at most `newest_count + 1` inodes PER TRACK -- six names for two
        tracks at the shipped settings.
        """
        found = self.candidates(track)
        chosen = found[:max(1, self.config.newest_count)]
        live = os.path.join(track.directory, track.live_name)
        if os.path.isfile(live) and not any(entry[2] == live for entry in chosen):
            chosen.append((0, track.live_name, live))
        return [(name, path, path == live) for _, name, path in chosen]

    # ------------------------------------------------------------------ promotion

    def promote(self, key, tag, live_names):
        """One pass over EVERY track: hardlink into evidence/, then sweep. Idempotent.

        Each track's live file is linked under its own `live_names[track]`, which is
        stable for the incident key -- a live file's SOURCE name (`console.log`,
        `detail.log`) is reused across incidents and across rollovers, so linking it
        under its own name would either collide or, worse, silently pin the wrong
        inode. A stable per-incident name makes the repeat passes of AD-16 free: the
        second and third attempts hit FileExistsError and skip.

        A track with no files -- an absent `/logs/detail/` on a host that has not
        taken the log4j2 change yet -- contributes nothing and is not an error.
        """
        self.ensure_dir()
        live_names = _live_names(live_names)
        # Degenerate path only (a caller that named no track, which record() and
        # load_pending both make impossible): generate rather than fall back to the
        # SOURCE name, which is reused across incidents and would pin the wrong
        # inode on the next rollover.
        for track_name, generated in live_names_for(slug(key), time.time()).items():
            live_names.setdefault(track_name, generated)
        linked, skipped, errors = [], [], []
        for track in self.config.tracks():
            for name, path, is_live in self.selection(track):
                destination = os.path.join(
                    self.config.evidence_dir,
                    live_names[track.name] if is_live else name)
                outcome, detail = self.link(path, destination, is_live)
                {"linked": linked, "skipped": skipped, "errors": errors}[outcome].append(detail)
        # Stamp the PROMOTION time (not the log's mtime) so the age sweep measures
        # how long we have held the evidence, which is what EVIDENCE_MAX_AGE_DAYS
        # advertises. A `skipped` name already has an earlier stamp and keeps it.
        self.remember_promoted(linked)

        result = {
            "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "key": key,
            "tag": tag,
            "linked": linked,
            "skipped": skipped,
            "errors": errors,
        }
        with self.lock:
            self.last_promotion = result
        log("promotion key=%r tag=%s linked=%s skipped=%s errors=%s"
            % (key, tag, linked or "-", skipped or "-", errors or "-"))
        self.sweep()
        return result

    def link(self, source, destination, is_live):
        """One hardlink. Returns ("linked"|"skipped"|"errors", basename-or-detail).

        An existing destination is normally just a previous pass of the same incident
        (AD-16's repeats), and re-linking the SAME inode is what "idempotent" means
        here -- so it is skipped, not an error.

        The one case where that is wrong is the live file. Its evidence name is pinned
        to the incident, but its SOURCE is a moving target: log4j2 renames console.log
        at rollover and creates a new one, so an incident still pending across a
        boundary (a group that has been dead for hours, or a crash loop retro-promoting
        each time) would otherwise have its later live inode silently dropped -- the
        name exists, so the link is refused, and the tail after the rollover is the part
        nobody has. When the existing name holds a DIFFERENT inode, the new one is
        pinned under a numbered sibling instead. It still costs zero blocks.
        """
        try:
            os.link(source, destination)
            return "linked", os.path.basename(destination)
        except FileExistsError:
            pass
        except OSError as error:
            # EXDEV lands here: evidence/ on another filesystem means hardlinks are
            # impossible, and the answer is NOT to fall back to copying (AD-14).
            log("ERROR cannot hardlink %s -> %s: %s" % (source, destination, error))
            return "errors", "%s: %s" % (os.path.basename(destination), error)

        if self.same_inode(source, destination) or not is_live:
            return "skipped", os.path.basename(destination)

        root, extension = os.path.splitext(destination)
        for suffix in range(2, 10):
            alternative = "%s-%d%s" % (root, suffix, extension)
            try:
                os.link(source, alternative)
                return "linked", os.path.basename(alternative)
            except FileExistsError:
                if self.same_inode(source, alternative):
                    return "skipped", os.path.basename(alternative)
            except OSError as error:
                log("ERROR cannot hardlink %s -> %s: %s" % (source, alternative, error))
                return "errors", "%s: %s" % (os.path.basename(alternative), error)
        return "errors", "%s: no free name for a rolled-over live file" % os.path.basename(root)

    @staticmethod
    def same_inode(one, other):
        try:
            return os.stat(one).st_ino == os.stat(other).st_ino
        except OSError:
            return True  # cannot tell => treat as already present rather than duplicate

    def ensure_dir(self):
        """AD-18: a SUBDIRECTORY of logs/, created here rather than by deploy.sh.

        `deploy.sh` is a temporary script that only mkdirs a fixed list and only
        runs `docker compose up`; depending on an edit to it would be a deployment
        step nobody performs. It must be a subdirectory (not a sibling) to stay on
        the same filesystem, and a subdirectory is exactly what escapes both
        sweepers: promtail's `__path__: /logs/*.log` is non-recursive and log4j2's
        Delete uses basePath /app/logs with maxDepth = 1.
        """
        try:
            os.makedirs(self.config.evidence_dir, exist_ok=True)
        except OSError as error:
            log("ERROR cannot create %s: %s" % (self.config.evidence_dir, error))

    # ------------------------------------------------------------------ scheduling

    def record(self, key, tag="alert", now=None):
        """Run pass 1 now and (re)arm passes 2 and 3 for this incident key.

        REFRESHES one pair of deadlines per key rather than stacking timers
        (AD-17): a deteriorating environment redelivering every 5 minutes would
        otherwise accumulate a pass for every redelivery, all of them doing the
        same idempotent work.
        """
        now = time.time() if now is None else now
        with self.lock:
            entry = self.pending.get(key)
            if entry is None:
                entry = {
                    "slug": slug(key),
                    "firstSeenAt": now,
                    # Timestamped from FIRST sight, so every pass for this incident
                    # links each track's live file under one name and the repeats are
                    # no-ops. One name PER TRACK since Phase 4 (AD-28).
                    "liveNames": live_names_for(slug(key), now),
                    "passes": 0,
                }
                self.pending[key] = entry
            entry["tag"] = tag
            entry["lastSeenAt"] = now
            entry["deferredAt"] = now + self.config.deferred_delay
            entry["tailAt"] = next_rollover(now, self.config.rollover_hours) + self.config.tail_delay
            entry["passes"] = entry.get("passes", 0) + 1
            live_names = dict(entry["liveNames"])
            self.enforce_pending_cap()
        result = self.promote(key, tag, live_names)
        self.save_pending()
        self.wake.set()
        return result

    def tick(self, now=None):
        """Run any pass whose deadline has arrived, and the periodic sweep."""
        now = time.time() if now is None else now
        due = []
        with self.lock:
            for key, entry in list(self.pending.items()):
                # Coerced, not compared raw: a non-numeric deadline that survived into
                # the dict must degrade to "run it now and be done with it", never
                # raise out of the scheduler loop.
                deferred = _deadline(entry.get("deferredAt"))
                tail = _deadline(entry.get("tailAt"))
                names = _live_names(entry.get("liveNames"))
                if entry.get("deferredAt") is not None and (deferred is None or now >= deferred):
                    entry["deferredAt"] = None
                    due.append((key, "deferred", names))
                if entry.get("tailAt") is not None and (tail is None or now >= tail):
                    entry["tailAt"] = None
                    due.append((key, "tail", names))
                if entry.get("deferredAt") is None and entry.get("tailAt") is None:
                    self.pending.pop(key, None)
        for key, tag, live_names in due:
            self.promote(key, tag, live_names)
        if now >= self.next_sweep:
            self.next_sweep = now + self.config.sweep_interval
            if not due:
                self.sweep()
        if due:
            self.save_pending()
        return due

    def next_deadline(self):
        """The soonest deadline. Total: an unusable entry can never poison min().

        This used to sit one line outside the scheduler's try/except and to trust
        whatever was in `self.pending`. `load_pending` now rejects unusable entries,
        and this coerces again as a second line of defence -- between them, no
        content of .pending.json can kill the timer thread.
        """
        with self.lock:
            deadlines = [deadline
                         for entry in self.pending.values()
                         for deadline in (_deadline(entry.get("deferredAt")),
                                          _deadline(entry.get("tailAt")))
                         if deadline is not None]
        sweep = _deadline(self.next_sweep)
        deadlines.append(sweep if sweep is not None else time.time() + self.config.sweep_interval)
        return min(deadlines)

    def enforce_pending_cap(self):
        """Bound `pending`, oldest incident first. Caller holds the lock."""
        while len(self.pending) > MAX_PENDING:
            oldest = min(self.pending,
                         key=lambda key: _deadline(self.pending[key].get("firstSeenAt")) or 0.0)
            self.pending.pop(oldest, None)
            self.evicted_pending += 1
            if self.evicted_pending == 1 or self.evicted_pending % 100 == 0:
                log("WARN pending-incident cap %d exceeded - dropped the oldest key %r "
                    "(%d dropped so far). Files already promoted are untouched; only this "
                    "incident's remaining deferred passes are lost."
                    % (MAX_PENDING, oldest, self.evicted_pending))

    # ------------------------------------------------------------------ persistence

    def save_pending(self):
        """Atomically persist the pending deadlines (tmp + os.replace)."""
        self.ensure_dir()
        with self.lock:
            snapshot = json.dumps(self.pending)
        self._write_atomically(self.config.pending_path, snapshot,
                               "cannot persist pending passes")

    def load_pending(self):
        loaded = self._read_json(self.config.pending_path)
        if not isinstance(loaded, dict):
            return
        restored, rejected = {}, []
        for key, entry in loaded.items():
            sane = _sane_entry(key, entry)
            if sane is None:
                rejected.append(key)
                continue
            restored[key] = sane
        with self.lock:
            self.pending = restored
            self.enforce_pending_cap()
        if rejected:
            # A hand-edit on the box, a schema change between shim versions, or a
            # file written by a newer version and read by a rolled-back one. Dropping
            # the entry costs one incident's deferred passes; keeping it used to cost
            # the scheduler thread -- min() over a str raises TypeError, the daemon
            # dies, HTTP keeps answering 200, and passes 2/3 plus the hourly sweep
            # never run again for ANY incident.
            log("WARN dropped %d unusable pending entr(y|ies) from %s: %s"
                % (len(rejected), self.config.pending_path, rejected))
        if self.pending:
            log("restored %d pending incident(s) from %s"
                % (len(self.pending), self.config.pending_path))

    # ------------------------------------------------------------- promotion times

    def remember_promoted(self, names, now=None):
        """Record when each freshly linked name was pinned, and persist."""
        if not names:
            return
        now = time.time() if now is None else now
        with self.lock:
            for name in names:
                self.promoted_at.setdefault(name, now)
        self.save_promoted()

    def save_promoted(self):
        self.ensure_dir()
        with self.lock:
            snapshot = json.dumps(self.promoted_at)
        self._write_atomically(self.config.promoted_path, snapshot,
                               "cannot persist promotion times")

    def load_promoted(self):
        loaded = self._read_json(self.config.promoted_path)
        if not isinstance(loaded, dict):
            return
        with self.lock:
            self.promoted_at = {name: value for name, value in loaded.items()
                                if isinstance(value, (int, float))
                                and not isinstance(value, bool)}

    def promotion_time(self, path, mtime):
        """When we pinned this file, falling back to its mtime when we have no record.

        The fallback is the pre-sidecar behaviour and it errs EARLY (an old log
        promoted today looks old), which is the safe direction for a size-pressured
        directory -- but it is why the sidecar is written on every pass rather than
        only on the first.
        """
        with self.lock:
            recorded = self.promoted_at.get(os.path.basename(path))
        return recorded if isinstance(recorded, (int, float)) else mtime

    # ------------------------------------------------------------------ io helpers

    @staticmethod
    def _read_json(path):
        try:
            with open(path) as handle:
                return json.load(handle)
        except (OSError, ValueError):
            return None

    @staticmethod
    def _write_atomically(path, payload, complaint):
        temporary = path + ".tmp"
        try:
            with open(temporary, "w") as handle:
                handle.write(payload)
            os.replace(temporary, path)
        except OSError as error:
            log("WARN %s: %s" % (complaint, error))

    # ------------------------------------------------------------------ boot / shutdown

    def boot(self):
        """AD-21: retro-promote when the previous run did not shut down cleanly.

        In a full-stack failure Alertmanager may be dead too, so the webhook cannot
        be the only path into this process. A start that finds no clean-shutdown
        marker assumes it is starting AFTER something bad and pins the newest two
        immediately, tagged `boot`.
        """
        self.ensure_dir()
        self.load_pending()
        self.load_promoted()
        clean = os.path.isfile(self.config.marker_path)
        self.started_clean = clean
        if clean:
            log("previous run shut down cleanly - no boot promotion")
            try:
                os.unlink(self.config.marker_path)
            except OSError as error:
                log("WARN cannot remove the clean-shutdown marker: %s" % error)
            return None
        log("no clean-shutdown marker in %s - the previous run did not stop cleanly; "
            "promoting the newest logs (tagged boot)" % self.config.evidence_dir)
        return self.record("boot", tag="boot")

    def shutdown(self):
        """Write the marker so the NEXT start knows this stop was deliberate."""
        self.ensure_dir()
        self.save_pending()
        try:
            with open(self.config.marker_path, "w") as handle:
                handle.write(time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()) + "\n")
            log("clean shutdown - marker written to %s" % self.config.marker_path)
        except OSError as error:
            log("WARN cannot write the clean-shutdown marker: %s" % error)

    # ------------------------------------------------------------------ sweep

    def evidence_files(self):
        """(mtime, size, path, inode-key) for every non-dotfile in evidence/, oldest first.

        `inode` is `(st_dev, st_ino)` and it is not decoration. Evidence entries are
        HARDLINKS, and AD-16 guarantees that at least one inode per track carries
        TWO names on every incident: pass 1 pins the live file as
        `detail-live-<slug>-<ts>.log`, and pass 3 pins the SAME inode again under
        its now-rolled name `detail-<date>.log`. Anything that accounts for bytes
        per NAME therefore double-counts the most expensive file in the directory,
        and anything that "frees" one name of a pair frees no blocks at all. See
        `sweep()`.
        """
        try:
            names = os.listdir(self.config.evidence_dir)
        except OSError:
            return []
        found = []
        for name in names:
            if name.startswith("."):
                continue  # .pending.json / .clean-shutdown are state, not evidence
            path = os.path.join(self.config.evidence_dir, name)
            try:
                stat = os.stat(path)
            except OSError:
                continue
            if not os.path.isfile(path):
                continue
            found.append((stat.st_mtime, stat.st_size, path, (stat.st_dev, stat.st_ino)))
        found.sort()
        return found

    def inode_groups(self, files=None):
        """Collapse evidence entries onto the blocks they actually occupy.

        One `InodeGroup` per distinct `(st_dev, st_ino)`, carrying EVERY name that
        points at it. This is the unit both the byte guard and `/health` count in:
        a group's `size` is one lot of bytes however many names it has, and
        evicting it means unlinking all of them, because that is the only thing
        that returns blocks to the filesystem.

        `promoted` is the OLDEST promotion stamp among the group's names — the
        moment we first decided this inode was evidence — so a second name added by
        a later pass neither refreshes its age nor moves it down the eviction order.
        `detail` is true if ANY name is a detail name; the two tracks are separate
        files so a mixed group cannot arise, and if one somehow did, the shorter age
        and the earlier eviction are the safe reading.
        """
        files = self.evidence_files() if files is None else files
        collected = {}
        for mtime, size, path, key in files:
            group = collected.get(key)
            if group is None:
                collected[key] = {"promoted": self.promotion_time(path, mtime),
                                  "mtime": mtime, "size": size,
                                  "detail": self.is_detail(path), "paths": [path]}
                continue
            group["promoted"] = min(group["promoted"], self.promotion_time(path, mtime))
            group["mtime"] = min(group["mtime"], mtime)
            group["size"] = max(group["size"], size)
            group["detail"] = group["detail"] or self.is_detail(path)
            group["paths"].append(path)
        groups = [InodeGroup(promoted=group["promoted"], mtime=group["mtime"],
                             size=group["size"], detail=group["detail"],
                             paths=sorted(group["paths"]))
                  for group in collected.values()]
        groups.sort(key=_eviction_order)
        return groups

    @staticmethod
    def is_detail(path):
        """Which track a promoted file came from, decided by its filename prefix.

        `detail-<date>.log` and `detail-live-<slug>-<ts>.log` both match; every
        aggregate name starts with `console`. A prefix rather than a sidecar lookup
        so the classification is still right for a file promoted by an older shim or
        linked by hand -- and so that misclassifying cannot silently make a 3 GB
        file immortal.
        """
        return os.path.basename(path).startswith(DETAIL_PREFIX)

    def sweep(self, now=None):
        """AD-19/AD-28: two ages, then detail-first eviction under the size guard.

        Age is measured from the PROMOTION, not from `st_mtime`. A hardlink shares
        the source inode, so a promoted file's mtime is the log's last-write time:
        log4j2 keeps rolled files for 7 days, so the older of the "newest two" can
        already be ~7 days old when it is pinned, and sweeping on mtime would have
        given it `EVIDENCE_MAX_AGE_DAYS - age_at_promotion` -- an effective ~7 days
        against an advertised 14. `.promoted.json` is what makes the advertised
        number the real one; a file with no record falls back to its mtime, which
        errs early rather than late.

        THE UNIT OF ACCOUNTING IS THE INODE, NOT THE DIRECTORY ENTRY. Evidence
        entries are hardlinks, and AD-16 guarantees that at least one inode per
        track carries two names on every incident -- pass 1 pins the live file,
        pass 3 pins the same inode again under its rolled name. Counting names
        would inflate one incident's real ~10.2 GB pin at 20k bots to ~13.6 GB and
        start evicting where the plan says it should not; worse, "freeing" one name
        of a pair returns ZERO blocks to the filesystem while crediting the guard
        with the whole `st_size`, so it under-shoots the cap, sweeps again, and
        eventually sheds twice the names the pressure called for. So the guard
        counts `inode_groups()` and evicting a group unlinks every one of its names
        -- the only operation that actually frees anything.

        Sizes are still `st_size`, so an evidence file whose SOURCE is still live is
        counted in full even though its blocks are shared with the log directory.
        That over-estimates, which is the safe direction: those blocks genuinely
        cannot be reclaimed while log4j2 holds them. Two evidence names for one
        evidence inode is a different thing and is NOT the safe direction, which is
        what the grouping fixes.

        TWO AGES AND AN ORDERED BYTE GUARD (AD-28). Detail files are 3.4-5.1 GB
        apiece at 20-30k bots and hold the ws-parser token material; aggregates are
        a few MB and hold the timeline. So detail gets `detail_max_age_days` (3),
        aggregates keep `max_age_days` (14), and under the byte cap EVERY detail
        candidate is evicted, oldest-promotion first (`_eviction_order`), before ANY
        aggregate is touched. The alternative -- one pool ordered purely by
        promotion time -- discards an old incident's 7 MB timeline to make room for
        a new incident's 3 GB payload, which is exactly backwards: it sheds the
        irreplaceable cheap bytes and keeps the expensive ones.
        """
        now = time.time() if now is None else now
        removed, freed = [], 0
        cutoff = {
            True: now - self.config.detail_max_age_days * 86400.0,
            False: now - self.config.max_age_days * 86400.0,
        }
        keep = {True: [], False: []}
        for group in self.inode_groups():
            if group.promoted <= cutoff[group.detail]:
                if self._unlink_group(group, removed):
                    freed += group.size
            else:
                keep[group.detail].append(group)
        # Oldest PROMOTION first within each class -- detail first, then aggregates.
        # The key is explicit because every file of one pass shares a promotion
        # stamp, so the tie-break decides the ordinary case: see `_eviction_order`.
        keep[True].sort(key=_eviction_order)
        keep[False].sort(key=_eviction_order)

        total = sum(group.size for groups in keep.values() for group in groups)
        for detail in (True, False):
            while total > self.config.max_bytes and keep[detail]:
                group = keep[detail].pop(0)
                if self._unlink_group(group, removed):
                    freed += group.size
                # Unconditional, as it has always been: a failed unlink must not
                # loop, and a concurrent sweep that already removed these names has
                # already accounted for the same bytes, so both runs converge.
                total -= group.size

        surviving = [path for detail in (True, False)
                     for group in keep[detail] for path in group.paths]
        self.forget_promoted(surviving)

        self.last_sweep = {
            "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now)),
            "removed": removed,
            "freedBytes": freed,
            "remaining": len(surviving),
            "remainingDetail": sum(len(group.paths) for group in keep[True]),
            "bytes": total,
        }
        if removed:
            log("sweep removed %d file(s) (%d bytes): %s" % (len(removed), freed, removed))
        return self.last_sweep

    def _unlink_group(self, group, removed):
        """Unlink every name of one inode; True if any of them went.

        All-or-nothing on the inode, because unlinking one name of a pair frees no
        blocks -- the whole point of grouping. Names already gone (a concurrent
        sweep got there first) are not an error and are not reported as removed.
        """
        gone = False
        for path in group.paths:
            if self._unlink(path):
                removed.append(os.path.basename(path))
                gone = True
        return gone

    def forget_promoted(self, surviving_paths):
        """Drop sidecar records for files that no longer exist, so it cannot grow."""
        surviving = {os.path.basename(path) for path in surviving_paths}
        with self.lock:
            stale = [name for name in self.promoted_at if name not in surviving]
            for name in stale:
                self.promoted_at.pop(name, None)
        if stale:
            self.save_promoted()

    @staticmethod
    def _unlink(path):
        try:
            os.unlink(path)
            return True
        except FileNotFoundError:
            # Already gone. Two sweeps can run at once (webhook thread + scheduler)
            # and both evict the same prefix deterministically, so this is the
            # ordinary outcome of the race, not a fault worth a WARN.
            return False
        except OSError as error:
            log("WARN cannot remove %s: %s" % (path, error))
            return False

    # ------------------------------------------------------------------ health

    def summary(self):
        files = self.evidence_files()
        with self.lock:
            pending = {key: {"tag": entry.get("tag"),
                             "firstSeenAt": entry.get("firstSeenAt"),
                             "passes": entry.get("passes"),
                             "deferredInSeconds": _remaining(entry.get("deferredAt")),
                             "tailInSeconds": _remaining(entry.get("tailAt"))}
                       for key, entry in self.pending.items()}
            last = self.last_promotion
        summary = self.config.summary()
        # Per-track, because "is the detail track being seen at all?" is the first
        # question a Phase 4 misconfiguration raises, and a single total cannot
        # answer it (P4-9). A zero detail count with a healthy aggregate count means
        # EVIDENCE_DETAIL_DIR is wrong or /logs/detail/ does not exist.
        #
        # `files` counts NAMES, `bytes` counts BLOCKS: one inode under two names
        # (the ordinary post-pass-3 state, AD-16) is two files and one lot of bytes.
        # Reported the same way the byte guard counts, so `evidenceBytes` and
        # EVIDENCE_MAX_BYTES are comparable on sight.
        groups = self.inode_groups(files)
        by_track = {AGGREGATE: [0, 0], DETAIL: [0, 0]}
        for group in groups:
            counter = by_track[DETAIL if group.detail else AGGREGATE]
            counter[0] += len(group.paths)
            counter[1] += group.size
        summary.update({
            "startedClean": self.started_clean,
            "evidenceFiles": len(files),
            "evidenceBytes": sum(group.size for group in groups),
            "evidenceByTrack": {name: {"files": counter[0], "bytes": counter[1]}
                                for name, counter in by_track.items()},
            "sourceFilesByTrack": {track.name: len(self.candidates(track))
                                   for track in self.config.tracks()},
            "pending": pending,
            "pendingCap": MAX_PENDING,
            "pendingEvicted": self.evicted_pending,
            # The timer thread runs passes 2 and 3 AND the hourly sweep. If it is
            # dead, HTTP still answers 200 and /health still looks fine, so the one
            # symptom used to be `deferredInSeconds` drifting ever more negative --
            # which nothing alerts on. Report it directly.
            "schedulerAlive": self.scheduler_alive(),
            "lastPromotion": last,
            "lastSweep": self.last_sweep,
        })
        return summary

    def scheduler_alive(self):
        thread = self.scheduler_thread
        return None if thread is None else thread.is_alive()


def _remaining(deadline):
    return None if deadline is None else round(deadline - time.time(), 1)


def describe(body):
    """(status, groupLabels, alertnames) from an Alertmanager payload. Never raises."""
    try:
        payload = json.loads(body.decode("utf-8"))
    except Exception:  # noqa: BLE001 - a malformed body must not stop a promotion
        return None, {}, None
    if not isinstance(payload, dict):
        return None, {}, None
    labels = payload.get("groupLabels")
    names = ",".join(sorted({
        (alert.get("labels") or {}).get("alertname", "?")
        for alert in payload.get("alerts") or []
        if isinstance(alert, dict)
    }))
    return payload.get("status"), labels if isinstance(labels, dict) else {}, names


class Handler(BaseHTTPRequestHandler):

    server_version = "evidence-shim/1.0"
    promoter = None  # injected by serve()

    def do_POST(self):  # noqa: N802 - BaseHTTPRequestHandler's naming
        # Total by construction, like viptalk-shim's: an exception escaping here
        # gives Alertmanager no HTTP response at all, which it treats as a transport
        # error and retries forever during the incident. 502 is the honest answer to
        # "something unexpected broke" and keeps the retry meaningful.
        try:
            body = self._read_body()
            status_label, group_labels, alert_names = describe(body)
            key = canonical_key(group_labels)
            log("webhook %s status=%s alerts=%s key=%r"
                % (self.path, status_label or "?", alert_names or "?", key))
            if status_label == "resolved":
                # send_resolved is false on this receiver, so this should be
                # unreachable. If reached: there is nothing to preserve about a
                # recovery, and promoting on it would pin post-incident noise.
                log("SKIP promotion - payload status=resolved")
                self._respond(200, {"status": "ignored", "reason": "resolved"})
                return
            result = self.promoter.record(key)
            self._respond(200, result)
        except Exception as error:  # noqa: BLE001 - answering matters more than the reason
            log("ERROR unhandled failure handling the webhook: %s: %s"
                % (type(error).__name__, error))
            self._respond(502, {"error": type(error).__name__})

    def do_GET(self):  # noqa: N802
        if self.path.startswith("/health"):
            self._respond(200, self.promoter.summary())
            return
        self._respond(404, {"error": "POST an Alertmanager webhook here, or GET /health"})

    def _read_body(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        # Always drain: leftover bytes on the socket break keep-alive and make
        # Alertmanager's next POST look like a transport error.
        return self.rfile.read(length) if length > 0 else b""

    def _respond(self, status, payload=None):
        data = json.dumps(payload if payload is not None else {"status": status}).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        log("http " + (fmt % args))


def serve(promoter):
    # Created here as well as in boot(), so the directory (and therefore the
    # same-filesystem answer on /health) exists from the moment this process
    # answers, not from the moment the first incident arrives.
    promoter.ensure_dir()
    Handler.promoter = promoter
    server = ThreadingHTTPServer(("0.0.0.0", promoter.config.port), Handler)
    log("evidence-shim listening on :%d %s"
        % (promoter.config.port, json.dumps(promoter.config.summary())))
    if promoter.config.same_filesystem() is False:
        log("ERROR %s and %s are on DIFFERENT filesystems - hardlinks are impossible and "
            "every promotion will fail. evidence/ must be a subdirectory of the logs mount."
            % (promoter.config.logs_dir, promoter.config.evidence_dir))
    return server


def scheduler(promoter, stop):
    """One thread, deadline-driven. Wakes early when a webhook arms a new pass.

    EVERYTHING that can raise is inside the guard, including the deadline
    computation. It used to sit one line below the try/except, so a `.pending.json`
    carrying a non-numeric deadline raised TypeError out of min(), killed this
    daemon thread, and left the process serving HTTP and answering 200 while
    passes 2 and 3 and the hourly sweep never ran again for any incident -- i.e.
    logs/evidence/ growing without bound, AD-19's exact failure mode, on the box
    that already died of a full disk.
    """
    while not stop.is_set():
        timeout = 30.0
        try:
            promoter.tick()
            timeout = max(1.0, min(30.0, promoter.next_deadline() - time.time()))
        except Exception as error:  # noqa: BLE001 - a bad pass must not kill the timer
            log("ERROR scheduled pass failed: %s: %s" % (type(error).__name__, error))
        promoter.wake.wait(timeout)
        promoter.wake.clear()
    log("scheduler stopped")


def main():
    promoter = Promoter(Config())
    promoter.boot()
    server = serve(promoter)

    stop = threading.Event()
    timer = threading.Thread(target=scheduler, args=(promoter, stop),
                             name="scheduler", daemon=True)
    promoter.scheduler_thread = timer
    timer.start()

    def terminate(signum, frame):  # noqa: ARG001 - signal handler signature
        log("received signal %s - shutting down" % signum)
        stop.set()
        promoter.wake.set()
        promoter.shutdown()
        # shutdown() must run from a thread that is not serve_forever's, or it deadlocks.
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)

    try:
        server.serve_forever()
    finally:
        stop.set()
    log("stopped")


if __name__ == "__main__":
    main()
