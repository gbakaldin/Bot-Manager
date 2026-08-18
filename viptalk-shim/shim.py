#!/usr/bin/env python3
"""Out-of-band VipTalk delivery for the one alert the app cannot deliver itself.

WHY THIS EXISTS (VIPTALK_ALERTING_V2 AD-V8b / AD-V9, requirement B3)
-------------------------------------------------------------------
Every other alert travels Prometheus -> Alertmanager -> bot-manager -> VipTalk.
`BotManagerDown` fires precisely when bot-manager is unscrapeable, i.e. when that
chain's third hop is dead, so the "the application is down" message cannot be
delivered by the application. This process is the second, independent hop: it
takes Alertmanager's webhook POST and re-emits it to VipTalk. It shares no code,
no database and no runtime with bot-manager, which is the entire point — putting
it inside bot-manager would reintroduce the bootstrap problem it exists to fix.

WHY A SHIM AT ALL
-----------------
Alertmanager's `webhook_configs` sends a fixed JSON schema and cannot template a
body, so it can never emit the `{"text": …, "roomIds": […]}` shape VipTalk wants.
Phase 0 (docs/reviews/VIPTALK_ALERTING_V2/spike.md) also proved VipTalk IGNORES
query parameters on `sendMessage` (it answers 400 M_CANNOT_SEND_EMPTY_MESSAGE),
which killed the "put the text in the receiver URL" design. A transform is
required either way. The same spike found VipTalk DOES accept an
`application/json` body, so this shim is a JSON->JSON field remap and needs no
form-encoding library — hence stdlib only, no dependencies, no build step, and a
stock `python:3.12-alpine` image with this file bind-mounted (the same posture as
prometheus.yml / alerts.yml / alertmanager.yml).

TWO REGISTERS, SAME AS THE APP (AD-V6)
--------------------------------------
An outage renders differently for the two audiences:

  * the ops room gets the TECHNICAL register — VIPTALK_OPS_ROOM_ID, the same room
    ID the app uses, taken from the same secrets.env entry;
  * product rooms get the CUSTOMER register — VIPTALK_DOWN_ROOM_IDS, non-technical
    wording, and empty on staging/loadtest so a non-prod restart never announces
    itself to a live product room (AD-V7).

Both texts are fixed strings: this process has no metrics, no Mongo and no idea
what broke. That is also why the Alertmanager receiver pointing here sets
`send_resolved: false` — a fixed string cannot say "recovered". The recovery half
is delivered by the normal app webhook, which by definition is reachable again
once `BotManagerDown` resolves (AD-V9).

Both of the app's switches are honoured here too: `VIPTALK_ENABLED=false` (the
documented master switch) disables this process entirely, and
`VIPTALK_CUSTOMER_NOTICES_ENABLED=false` suppresses the customer register while
leaving the ops one — so "alerting is off" means off on both paths, and AD-V7's
one policy has one answer rather than two that can disagree.

The instance label is stamped into both texts (AD-7 / AD-V15): prod, loadtest and
staging run the same artifact into the same ops room, and it is the only thing
that tells them apart.

BEHAVIOUR
---------
  POST <any path>   Alertmanager webhook. Sends the configured registers.
                    200 when everything that had somewhere to go was accepted (or
                    when there was nothing to send); 502 on a VipTalk failure, so
                    Alertmanager retries (VIPTALK_ALERTING AD-8, matching what
                    bot-manager's own webhook endpoint answers).
  GET  /health      200 + a config summary with the token masked, plus `lastSend` —
                    the outcome of the last REAL send attempt (null if there has not
                    been one). It never probes VipTalk: this is the compose
                    healthcheck, and a probe every 30 s in three instances would put a
                    standing synthetic load on a third-party messenger. So it answers
                    "is this configured, and what happened last time it tried", and is
                    careful not to imply more — `baseUrlWellFormed` is a check on the
                    URL string alone.

Deliberately permissive about the request path and the request body: a typo in
the receiver URL, or an Alertmanager payload-schema change, must not be the
reason an outage notice is dropped. The one thing that IS inspected is
`status`: a payload explicitly marked `resolved` is not sent, so flipping
`send_resolved` on by mistake cannot publish "the application is down" at the
moment it came back.

Self-test: `python3 viptalk-shim/selftest.py` (no network, no containers).
"""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Customer register. Kept byte-identical to the `public_summary` annotation on
# BotManagerDown in prometheus/alerts.yml: the same event must read the same way
# whether it was delivered here (app down) or by the app (app back up).
DEFAULT_DOWN_TEXT = (
    "ALERT! Bot Management application is experiencing issues, "
    "backend team is aware and will deliver fixes soon."
)

# Technical register. Says out loud that it came from the out-of-band path, because
# the absence of the usual app-formatted message is itself information.
DEFAULT_OPS_TEXT = (
    "\U0001F534 CRITICAL — bot-manager is not scrapeable\n"
    "Prometheus has failed to scrape bot-manager — the app is down, wedged, or the "
    "container was replaced. In-app alerting is down with it, so every other alert is "
    "silent until it returns.\n"
    "— via viptalk-shim (out-of-band; the app could not deliver this)"
)

# api.viptalk.org sits behind Cloudflare, and Cloudflare blocks urllib's default
# `Python-urllib/3.12` on browser signature: HTTP 403, error 1010, "the site owner has
# blocked access based on your browser's signature". Isolated on 2026-08-18 by holding URL,
# token, room and host fixed and varying ONLY this header — curl's default and
# `Java-http-client/21.0.2` both delivered, `Python-urllib/3.12` did not. So every send
# from this container failed while /health cheerfully reported the channel usable.
#
# Honest, not a disguise: it identifies the actual client. It is deliberately NOT a browser
# string — impersonating Chrome would be both a lie and a thing that ages badly. The Java
# path (VipTalkClient) is unaffected and is intentionally left alone.
USER_AGENT = "viptalk-shim/1.0 (bot-manager alerting; +https://github.com/vingame)"


def _env(name, default=""):
    value = os.environ.get(name)
    return default if value is None else value.strip()


def _rooms(raw):
    """Space- or comma-separated Matrix room IDs -> list, blanks dropped."""
    return [room for room in raw.replace(",", " ").split() if room]


def _mask(token):
    return "<unset>" if not token else token[:4] + "…(" + str(len(token)) + " chars)"


def _number(name, raw, default, cast):
    """A numeric env var, falling back LOUDLY to `default` on anything unparseable.

    `VIPTALK_TIMEOUT_SECONDS=10s` or `VIPTALK_SHIM_PORT=8O80` used to raise straight
    out of Config(), before the server binds — under `restart: unless-stopped` that is
    a crash loop, i.e. the shim is down for precisely the outage it exists to report,
    with the only trace in `docker compose logs`. These values are hand-edited on the
    host, so a typo is likely; the same posture as `enabled` applies — carry on and say
    so, rather than die.
    """
    if not raw:
        return default
    try:
        return cast(raw)
    except (TypeError, ValueError):
        log("WARN %s=%r is not a number — falling back to %r" % (name, raw, default))
        return default


def _flag(raw, default):
    """A boolean env var. Anything unrecognised keeps `default` rather than raising."""
    value = (raw or "").strip().lower()
    if value in ("1", "true", "yes", "on"):
        return True
    if value in ("0", "false", "no", "off"):
        return False
    return default


class Config(object):
    """Everything this process knows, resolved once at startup from the environment."""

    def __init__(self, environ=None):
        get = (lambda name, default="": _env(name, default)) if environ is None \
            else (lambda name, default="": (environ.get(name) or default).strip())

        self.base_url = get("VIPTALK_BASE_URL", "https://api.viptalk.org").rstrip("/")
        self.token = get("VIPTALK_BOT_TOKEN")
        self.instance_label = get("VIPTALK_INSTANCE_LABEL")
        self.timeout = _number("VIPTALK_TIMEOUT_SECONDS", get("VIPTALK_TIMEOUT_SECONDS", "10"), 10.0, float)
        self.port = _number("VIPTALK_SHIM_PORT", get("VIPTALK_SHIM_PORT", "8080"), 8080, int)

        # The two app-side switches, honoured here too. Before this the shim read
        # neither: an operator who set VIPTALK_ENABLED=false — the documented "master
        # switch" — silenced the app and left this process armed, so the next
        # BotManagerDown still published, including customer-facing copy into a live
        # product room. Default true so an unset variable keeps the delivery path alive
        # (the app's own default is false; the difference is deliberate — the app-down
        # notice is the one you least want a *missing* variable to suppress).
        self.enabled_flag = _flag(get("VIPTALK_ENABLED", "true"), True)
        # AD-V7's one flag for customer copy. The room list is still required as well;
        # this makes the app's flag able to veto, which is what "one policy" means.
        self.customer_notices = _flag(get("VIPTALK_CUSTOMER_NOTICES_ENABLED", "true"), True)

        self.ops_rooms = _rooms(get("VIPTALK_OPS_ROOM_ID"))
        self.down_rooms = _rooms(get("VIPTALK_DOWN_ROOM_IDS"))
        self.ops_text = get("VIPTALK_DOWN_OPS_TEXT") or DEFAULT_OPS_TEXT
        self.down_text = get("VIPTALK_DOWN_TEXT") or DEFAULT_DOWN_TEXT

        # Outcome of the last REAL send attempt: (ok, when, detail). None until one has
        # been made. See `last_send` for why /health reports this.
        self._last_send = None

    @property
    def base_url_well_formed(self):
        """The base URL PARSES as something urllib can open. Says nothing about reachability.

        A scheme-less `VIPTALK_BASE_URL` (`api.viptalk.org`) is an ordinary secrets.env
        typo, and `Request()` raises `ValueError` on it — with the token in the message.
        Checked once here so the state reads as "not configured" instead of failing on
        every notification.

        Named for what it does since 2026-08-18. It used to be `base_url_usable` and was
        published on /health as `baseUrlUsable`, which read as "the delivery path works" —
        and it reported exactly that while Cloudflare was 403ing 100% of sends. A string
        check must not be able to claim that; what the channel is actually doing is in
        `last_send`.
        """
        return urllib.parse.urlsplit(self.base_url).scheme in ("http", "https")

    def record_send(self, ok, detail=None):
        """Remember how the last real send went, for /health to report.

        Deliberately passive: /health must NOT probe VipTalk. It is the compose
        healthcheck and runs every 30 s in three instances — a probe there would put a
        standing synthetic load on a third-party messenger and could itself get the shim
        rate-limited or blocked, i.e. break the thing it is checking. This costs nothing
        and reports the only evidence that actually counts: what happened the last time
        this process really tried.
        """
        self._last_send = (bool(ok), time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                           self.redact(detail) if detail else None)

    @property
    def last_send(self):
        """`{outcome, at, detail}` for the last send, or None if nothing has been sent yet.

        None is an honest answer and NOT a failure: a healthy instance may go weeks
        without an outage to report. It means "no evidence either way", which is exactly
        what `baseUrlUsable: true` was silently pretending not to be.
        """
        if self._last_send is None:
            return None
        ok, when, detail = self._last_send
        summary = {"outcome": "sent" if ok else "failed", "at": when}
        if detail:
            summary["detail"] = detail
        return summary

    @property
    def enabled(self):
        """A token, a usable base URL, at least one room, and the master switch on.

        Blank config is a normal, expected state (a host with no secrets.env, or a
        non-prod instance with no product rooms wired), so it self-disables and says
        so rather than refusing to start — a crash-looping container would be one
        more thing broken during the outage this is meant to report.
        """
        return (self.enabled_flag
                and bool(self.token)
                and self.base_url_well_formed
                and bool(self.ops_rooms or self.down_rooms))

    def registers(self):
        """(name, text, rooms) for each register that has somewhere to go."""
        out = []
        if self.ops_rooms:
            out.append(("technical", self._stamped(self.ops_text), self.ops_rooms))
        if self.down_rooms and self.customer_notices:
            out.append(("customer", self._stamped(self.down_text), self.down_rooms))
        return out

    def redact(self, text):
        """Strip the bot token out of anything on its way to a log.

        The token is in the URL PATH, and VipTalk echoes the request path back in the
        `path` field of its error bodies (docs/reviews/VIPTALK_ALERTING_V2/spike.md) —
        so the most likely 4xx of all, a wrong or expired token, would otherwise write
        the token to stderr, into the container's json-file log, into Loki, forever.
        Some exceptions (`InvalidURL`) carry the URL too. Exact substring replace: the
        token is a known string here, so no false positives and no misses.
        """
        if not text or not self.token:
            return text
        return text.replace(self.token, "***")

    def _stamped(self, text):
        """Prefix the instance label so a shared room can tell prod from staging.

        Absent label => no segment at all, never a guess: a message headed `staging`
        on the prod box is worse than one headed nothing (AD-V15).
        """
        return text if not self.instance_label else "[" + self.instance_label + "] " + text

    def summary(self):
        return {
            "enabled": self.enabled,
            "baseUrl": self.base_url,
            # Renamed from `baseUrlUsable` (2026-08-18): it is a check on the STRING, and
            # under the old name /health read as proof the channel worked — while every
            # send was being 403ed by Cloudflare. The name now claims only what is true.
            "baseUrlWellFormed": self.base_url_well_formed,
            # The honest health signal: what happened on the last real attempt. null until
            # there has been one. An operator reading this can tell "never tried" from
            # "tried and rejected", which is the distinction that was missing.
            "lastSend": self.last_send,
            "token": _mask(self.token),
            "instanceLabel": self.instance_label or None,
            "opsRooms": len(self.ops_rooms),
            # Rooms wired vs rooms that will actually be used: with
            # VIPTALK_CUSTOMER_NOTICES_ENABLED=false the customer register is off even
            # though the room list is populated, and /health is where an operator finds
            # that out without staging an outage.
            "productRooms": len(self.down_rooms),
            "customerNoticesEnabled": self.customer_notices,
            "masterSwitch": self.enabled_flag,
        }


def log(message):
    sys.stderr.write(time.strftime("%Y-%m-%d %H:%M:%S ") + message + "\n")
    sys.stderr.flush()


def send(config, text, rooms):
    """POST one message to VipTalk. Returns True iff it was accepted.

    Never raises: a failure here must become a 502 to Alertmanager (which retries),
    not a stack trace that leaves the notification in an unknown state.
    """
    try:
        url = config.base_url + "/v1/bot/" + config.token + "/sendMessage"
        body = json.dumps({"text": text, "roomIds": rooms}).encode("utf-8")
        # Request() is what PARSES the url, so it must be inside the try: a scheme-less
        # VIPTALK_BASE_URL raised ValueError from here, which escaped send -> deliver ->
        # do_POST, printed the token in a traceback, and left Alertmanager with NO
        # response at all — during the outage. `enabled` now rejects that config up
        # front; this is the belt to that braces.
        request = urllib.request.Request(
            url, data=body, method="POST",
            headers={"Content-Type": "application/json",
                     "Accept": "application/json",
                     # Load-bearing — see USER_AGENT. Without it urllib supplies
                     # `Python-urllib/3.12` and Cloudflare 403s every single send.
                     "User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=config.timeout) as response:
            response.read()
            status = response.status
        accepted = 200 <= status < 300
        config.record_send(accepted, None if accepted else "HTTP %s" % status)
        return accepted
    except urllib.error.HTTPError as error:
        detail = ""
        try:
            detail = error.read().decode("utf-8", "replace")[:300]
        except Exception:  # noqa: BLE001 - diagnostics only, never worth failing over
            pass
        log("ERROR VipTalk rejected the message: HTTP %s %s"
            % (error.code, config.redact(detail)))
        config.record_send(False, "HTTP %s %s" % (error.code, config.redact(detail)[:120]))
        return False
    except Exception as error:  # noqa: BLE001 - URLError, socket timeout, DNS, TLS…
        # type + redacted str, never repr: repr on InvalidURL and friends embeds the
        # full URL, and the token is in it.
        log("ERROR VipTalk unreachable: %s: %s"
            % (type(error).__name__, config.redact(str(error))))
        config.record_send(False, "%s: %s" % (type(error).__name__,
                                              config.redact(str(error))[:120]))
        return False


def deliver(config, payload_status, sender=send):
    """Send every configured register. Returns the HTTP status to answer with."""
    if not config.enabled:
        # 200, not 502: a deliberately unconfigured instance must not make
        # Alertmanager retry forever over something that will never succeed.
        log("SKIP notification — shim is not configured "
            "(enabled=%s, token=%s, baseUrlWellFormed=%s, opsRooms=%d, productRooms=%d)"
            % (config.enabled_flag, _mask(config.token), config.base_url_well_formed,
               len(config.ops_rooms), len(config.down_rooms)))
        return 200

    if payload_status == "resolved":
        # send_resolved is false on this receiver, so this should be unreachable.
        # If it ever is reached, publishing the static outage text on recovery would
        # be actively misleading — the recovery half is the app's job (AD-V9).
        log("SKIP notification — payload status=resolved; recovery is delivered by the app path")
        return 200

    ok = True
    for name, text, rooms in config.registers():
        accepted = sender(config, text, rooms)
        log("%s register -> %d room(s): %s" % (name, len(rooms), "sent" if accepted else "FAILED"))
        ok = ok and accepted
    # 502 mirrors what bot-manager's own webhook endpoint answers on a VipTalk
    # failure, and is what makes Alertmanager's retry meaningful.
    return 200 if ok else 502


class Handler(BaseHTTPRequestHandler):

    server_version = "viptalk-shim/1.0"
    config = None  # injected by serve()

    def do_POST(self):  # noqa: N802 - BaseHTTPRequestHandler's naming
        # Total by construction. This handler is the last line of defence for a process
        # whose whole job is to ANSWER during an incident: an exception escaping here
        # makes socketserver print a traceback and close the connection with no HTTP
        # response at all, which Alertmanager sees as a transport error and retries —
        # re-raising, forever, while the outage is in progress. 502 is the honest answer
        # to "something unexpected broke", and it keeps the retry meaningful.
        try:
            body = self._read_body()
            status_label, alert_names = _describe(body)
            log("webhook %s status=%s alerts=%s"
                % (self.path, status_label or "?", alert_names or "?"))
            status = deliver(self.config, status_label)
        except Exception as error:  # noqa: BLE001 - answering matters more than the reason
            config = self.config
            detail = config.redact(str(error)) if config else str(error)
            log("ERROR unhandled failure handling the webhook: %s: %s"
                % (type(error).__name__, detail))
            status = 502
        self._respond(status)

    def do_GET(self):  # noqa: N802
        if self.path.startswith("/health"):
            self._respond(200, self.config.summary())
            return
        self._respond(404, {"error": "POST an Alertmanager webhook here, or GET /health"})

    def _read_body(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        # Always drain the body even though the text is fixed: leaving bytes on the
        # socket breaks keep-alive and makes Alertmanager's next POST look like a
        # transport error.
        return self.rfile.read(length) if length > 0 else b""

    def _respond(self, status, payload=None):
        data = json.dumps(payload if payload is not None else {"status": status}).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        # BaseHTTPRequestHandler's default writes an unrelated format to stderr;
        # route it through ours so container logs are one shape.
        log("http " + (fmt % args))


def _describe(body):
    """(status, alertnames) from an Alertmanager payload — best effort, never raises."""
    try:
        payload = json.loads(body.decode("utf-8"))
        names = ",".join(sorted({
            (alert.get("labels") or {}).get("alertname", "?")
            for alert in payload.get("alerts") or []
        }))
        return payload.get("status"), names
    except Exception:  # noqa: BLE001 - a malformed body must not stop delivery
        return None, None


def serve(config):
    Handler.config = config
    server = ThreadingHTTPServer(("0.0.0.0", config.port), Handler)
    log("viptalk-shim listening on :%d %s" % (config.port, json.dumps(config.summary())))
    if not config.enabled:
        log("WARN shim is NOT configured — it will accept webhooks and send nothing. "
            "Set VIPTALK_ENABLED=true and VIPTALK_BOT_TOKEN plus VIPTALK_OPS_ROOM_ID "
            "and/or VIPTALK_DOWN_ROOM_IDS in secrets.env on this host.")
    if config.token and not config.base_url_well_formed:
        log("ERROR VIPTALK_BASE_URL=%r has no http/https scheme — nothing can be sent."
            % (config.base_url,))
    return server


def main():
    server = serve(Config())
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log("shutting down")
        server.shutdown()


if __name__ == "__main__":
    main()
