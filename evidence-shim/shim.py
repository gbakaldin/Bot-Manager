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

NEWEST TWO AT EXECUTION TIME (AD-15)
------------------------------------
Files are chosen by mtime at the moment a pass runs, never by arithmetic on the
alert's timestamp. This re-centres the window automatically: an incident at
minute 118 of a 2 h period yields a previous file holding 58 min of normal
operation plus 2 min of the incident, and a live file holding the rest, with the
ancient period falling off by itself. Timestamp arithmetic would need clock-skew
handling and would still pick the wrong file at a rollover boundary.

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
                    promotion, and the evidence directory's file count and size.
                    Never touches bot-manager, never touches the network.

Deliberately permissive about the request path and the body shape, for the same
reason viptalk-shim is: a typo in a receiver URL or an Alertmanager schema change
must not be the reason an incident's logs were swept.

Self-test: `python3 evidence-shim/selftest.py` (no network, no containers).
"""

import fnmatch
import json
import os
import signal
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# The live file log4j2 writes to, and the glob covering it plus its rolled
# siblings (`console-%d{yyyy-MM-dd-HH}.log`). Kept in step with
# logging/log4j2.properties -- if that filePattern changes, this changes.
LIVE_NAME = "console.log"
LOG_GLOB = "console*.log"

# Written on SIGTERM, removed on start. Its ABSENCE at start is what tells us the
# previous run died rather than stopped (AD-21).
CLEAN_MARKER = ".clean-shutdown"
# Scheduled passes survive a restart of this container, so a shim redeploy in the
# middle of an incident does not silently drop pass 2 and pass 3.
PENDING_FILE = ".pending.json"

# How many files "newest two" means (AD-15). Not an env var in docker-compose.yml
# on purpose: it is a design constant of the window, not an operational knob.
DEFAULT_NEWEST_COUNT = 2


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
        self.port = _number("EVIDENCE_SHIM_PORT", get("EVIDENCE_SHIM_PORT", "8080"), 8080, int)

        # AD-19: evidence/ escapes both sweepers, so it needs its own or the fix for
        # unbounded growth is itself unbounded growth. Age first, then oldest-first
        # until under the size guard. 0 days is a VALID setting meaning "sweep
        # everything" (it is how the guard is verified), not "disabled".
        self.max_age_days = _number("EVIDENCE_MAX_AGE_DAYS",
                                    get("EVIDENCE_MAX_AGE_DAYS", "14"), 14.0, float)
        self.max_bytes = _number("EVIDENCE_MAX_BYTES",
                                 get("EVIDENCE_MAX_BYTES", str(5 * 1024 ** 3)),
                                 5 * 1024 ** 3, int)
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

    @property
    def marker_path(self):
        return os.path.join(self.evidence_dir, CLEAN_MARKER)

    @property
    def pending_path(self):
        return os.path.join(self.evidence_dir, PENDING_FILE)

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
            "evidenceDir": self.evidence_dir,
            "sameFilesystem": self.same_filesystem(),
            "maxAgeDays": self.max_age_days,
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
        # key -> {"slug", "liveName", "firstSeenAt", "deferredAt", "tailAt", "passes"}
        self.pending = {}
        self.last_promotion = None
        self.last_sweep = None
        self.next_sweep = time.time() + config.sweep_interval
        self.started_clean = None

    # ------------------------------------------------------------------ selection

    def candidates(self):
        """`console*.log` in logs_dir, newest first. Never recurses, never raises."""
        directory = self.config.logs_dir
        try:
            names = os.listdir(directory)
        except OSError as error:
            log("ERROR cannot list %s: %s" % (directory, error))
            return []
        found = []
        for name in names:
            if not fnmatch.fnmatch(name, LOG_GLOB):
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

    def selection(self):
        """The newest N plus the live file (AD-15). Returns [(name, path, is_live)]."""
        found = self.candidates()
        chosen = found[:max(1, self.config.newest_count)]
        live = os.path.join(self.config.logs_dir, LIVE_NAME)
        if os.path.isfile(live) and not any(entry[2] == live for entry in chosen):
            chosen.append((0, LIVE_NAME, live))
        return [(name, path, path == live) for _, name, path in chosen]

    # ------------------------------------------------------------------ promotion

    def promote(self, key, tag, live_name):
        """One pass: hardlink the selection into evidence/, then sweep. Idempotent.

        The live file is linked under `live_name`, which is stable for the incident
        key -- its SOURCE name (`console.log`) is reused across incidents and across
        rollovers, so linking it under its own name would either collide or, worse,
        silently pin the wrong inode. A stable per-incident name makes the repeat
        passes of AD-16 free: the second and third attempts hit FileExistsError and
        skip.
        """
        self.ensure_dir()
        linked, skipped, errors = [], [], []
        for name, path, is_live in self.selection():
            destination = os.path.join(self.config.evidence_dir,
                                       live_name if is_live else name)
            outcome, detail = self.link(path, destination, is_live)
            {"linked": linked, "skipped": skipped, "errors": errors}[outcome].append(detail)

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
                    # links the live file under one name and the repeats are no-ops.
                    "liveName": "console-live-%s-%s.log"
                                % (slug(key), time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(now))),
                    "passes": 0,
                }
                self.pending[key] = entry
            entry["tag"] = tag
            entry["lastSeenAt"] = now
            entry["deferredAt"] = now + self.config.deferred_delay
            entry["tailAt"] = next_rollover(now, self.config.rollover_hours) + self.config.tail_delay
            entry["passes"] = entry.get("passes", 0) + 1
            live_name = entry["liveName"]
        result = self.promote(key, tag, live_name)
        self.save_pending()
        self.wake.set()
        return result

    def tick(self, now=None):
        """Run any pass whose deadline has arrived, and the periodic sweep."""
        now = time.time() if now is None else now
        due = []
        with self.lock:
            for key, entry in list(self.pending.items()):
                if entry.get("deferredAt") is not None and now >= entry["deferredAt"]:
                    entry["deferredAt"] = None
                    due.append((key, "deferred", entry["liveName"]))
                if entry.get("tailAt") is not None and now >= entry["tailAt"]:
                    entry["tailAt"] = None
                    due.append((key, "tail", entry["liveName"]))
                if entry.get("deferredAt") is None and entry.get("tailAt") is None:
                    self.pending.pop(key, None)
        for key, tag, live_name in due:
            self.promote(key, tag, live_name)
        if now >= self.next_sweep:
            self.next_sweep = now + self.config.sweep_interval
            if not due:
                self.sweep()
        if due:
            self.save_pending()
        return due

    def next_deadline(self):
        with self.lock:
            deadlines = [value
                         for entry in self.pending.values()
                         for value in (entry.get("deferredAt"), entry.get("tailAt"))
                         if value is not None]
        deadlines.append(self.next_sweep)
        return min(deadlines)

    # ------------------------------------------------------------------ persistence

    def save_pending(self):
        """Atomically persist the pending deadlines (tmp + os.replace)."""
        self.ensure_dir()
        with self.lock:
            snapshot = json.dumps(self.pending)
        temporary = self.config.pending_path + ".tmp"
        try:
            with open(temporary, "w") as handle:
                handle.write(snapshot)
            os.replace(temporary, self.config.pending_path)
        except OSError as error:
            log("WARN cannot persist pending passes: %s" % error)

    def load_pending(self):
        try:
            with open(self.config.pending_path) as handle:
                loaded = json.load(handle)
        except (OSError, ValueError):
            return
        if not isinstance(loaded, dict):
            return
        with self.lock:
            self.pending = {key: entry for key, entry in loaded.items()
                            if isinstance(entry, dict) and entry.get("liveName")}
        if self.pending:
            log("restored %d pending incident(s) from %s"
                % (len(self.pending), self.config.pending_path))

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
        """(mtime, size, path) for every non-dotfile in evidence/, oldest first."""
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
            found.append((stat.st_mtime, stat.st_size, path))
        found.sort()
        return found

    def sweep(self, now=None):
        """AD-19: age first, then oldest-first until under the size guard.

        Sizes are `st_size`, so a promoted file that is still live is counted in
        full even though its blocks are shared with the original. That
        over-estimates, which is the safe direction for a guard whose job is to
        stop this directory becoming the new unbounded thing.
        """
        now = time.time() if now is None else now
        removed, freed = [], 0
        cutoff = now - self.config.max_age_days * 86400.0
        files = self.evidence_files()
        keep = []
        for mtime, size, path in files:
            if mtime <= cutoff:
                if self._unlink(path):
                    removed.append(os.path.basename(path))
                    freed += size
            else:
                keep.append((mtime, size, path))

        total = sum(size for _, size, _ in keep)
        while total > self.config.max_bytes and keep:
            mtime, size, path = keep.pop(0)
            if self._unlink(path):
                removed.append(os.path.basename(path))
                freed += size
            total -= size

        self.last_sweep = {
            "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now)),
            "removed": removed,
            "freedBytes": freed,
            "remaining": len(keep),
            "bytes": total,
        }
        if removed:
            log("sweep removed %d file(s) (%d bytes): %s" % (len(removed), freed, removed))
        return self.last_sweep

    @staticmethod
    def _unlink(path):
        try:
            os.unlink(path)
            return True
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
        summary.update({
            "startedClean": self.started_clean,
            "evidenceFiles": len(files),
            "evidenceBytes": sum(size for _, size, _ in files),
            "pending": pending,
            "lastPromotion": last,
            "lastSweep": self.last_sweep,
        })
        return summary


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
    """One thread, deadline-driven. Wakes early when a webhook arms a new pass."""
    while not stop.is_set():
        try:
            promoter.tick()
        except Exception as error:  # noqa: BLE001 - a bad pass must not kill the timer
            log("ERROR scheduled pass failed: %s: %s" % (type(error).__name__, error))
        timeout = max(1.0, min(30.0, promoter.next_deadline() - time.time()))
        promoter.wake.wait(timeout)
        promoter.wake.clear()


def main():
    promoter = Promoter(Config())
    promoter.boot()
    server = serve(promoter)

    stop = threading.Event()
    threading.Thread(target=scheduler, args=(promoter, stop), daemon=True).start()

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
