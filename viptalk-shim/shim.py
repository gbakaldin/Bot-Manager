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
  GET  /health      200 + a config summary with the token masked. This is how the
                    delivery path is checked WITHOUT waiting for an outage.

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


def _env(name, default=""):
    value = os.environ.get(name)
    return default if value is None else value.strip()


def _rooms(raw):
    """Space- or comma-separated Matrix room IDs -> list, blanks dropped."""
    return [room for room in raw.replace(",", " ").split() if room]


def _mask(token):
    return "<unset>" if not token else token[:4] + "…(" + str(len(token)) + " chars)"


class Config(object):
    """Everything this process knows, resolved once at startup from the environment."""

    def __init__(self, environ=None):
        get = (lambda name, default="": _env(name, default)) if environ is None \
            else (lambda name, default="": (environ.get(name) or default).strip())

        self.base_url = get("VIPTALK_BASE_URL", "https://api.viptalk.org").rstrip("/")
        self.token = get("VIPTALK_BOT_TOKEN")
        self.instance_label = get("VIPTALK_INSTANCE_LABEL")
        self.timeout = float(get("VIPTALK_TIMEOUT_SECONDS", "10") or "10")
        self.port = int(get("VIPTALK_SHIM_PORT", "8080") or "8080")

        self.ops_rooms = _rooms(get("VIPTALK_OPS_ROOM_ID"))
        self.down_rooms = _rooms(get("VIPTALK_DOWN_ROOM_IDS"))
        self.ops_text = get("VIPTALK_DOWN_OPS_TEXT") or DEFAULT_OPS_TEXT
        self.down_text = get("VIPTALK_DOWN_TEXT") or DEFAULT_DOWN_TEXT

    @property
    def enabled(self):
        """A token and at least one room, or there is nothing this process can do.

        Blank config is a normal, expected state (a host with no secrets.env, or a
        non-prod instance with no product rooms wired), so it self-disables and says
        so rather than refusing to start — a crash-looping container would be one
        more thing broken during the outage this is meant to report.
        """
        return bool(self.token) and bool(self.ops_rooms or self.down_rooms)

    def registers(self):
        """(name, text, rooms) for each register that has somewhere to go."""
        out = []
        if self.ops_rooms:
            out.append(("technical", self._stamped(self.ops_text), self.ops_rooms))
        if self.down_rooms:
            out.append(("customer", self._stamped(self.down_text), self.down_rooms))
        return out

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
            "token": _mask(self.token),
            "instanceLabel": self.instance_label or None,
            "opsRooms": len(self.ops_rooms),
            "productRooms": len(self.down_rooms),
        }


def log(message):
    sys.stderr.write(time.strftime("%Y-%m-%d %H:%M:%S ") + message + "\n")
    sys.stderr.flush()


def send(config, text, rooms):
    """POST one message to VipTalk. Returns True iff it was accepted.

    Never raises: a failure here must become a 502 to Alertmanager (which retries),
    not a stack trace that leaves the notification in an unknown state.
    """
    url = config.base_url + "/v1/bot/" + config.token + "/sendMessage"
    body = json.dumps({"text": text, "roomIds": rooms}).encode("utf-8")
    request = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=config.timeout) as response:
            response.read()
            return 200 <= response.status < 300
    except urllib.error.HTTPError as error:
        detail = ""
        try:
            detail = error.read().decode("utf-8", "replace")[:300]
        except Exception:  # noqa: BLE001 - diagnostics only, never worth failing over
            pass
        log("ERROR VipTalk rejected the message: HTTP %s %s" % (error.code, detail))
        return False
    except Exception as error:  # noqa: BLE001 - URLError, socket timeout, DNS, TLS…
        log("ERROR VipTalk unreachable: %r" % (error,))
        return False


def deliver(config, payload_status, sender=send):
    """Send every configured register. Returns the HTTP status to answer with."""
    if not config.enabled:
        # 200, not 502: a deliberately unconfigured instance must not make
        # Alertmanager retry forever over something that will never succeed.
        log("SKIP notification — shim is not configured (token=%s, opsRooms=%d, productRooms=%d)"
            % (_mask(config.token), len(config.ops_rooms), len(config.down_rooms)))
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
        body = self._read_body()
        status_label, alert_names = _describe(body)
        log("webhook %s status=%s alerts=%s" % (self.path, status_label or "?", alert_names or "?"))
        self._respond(deliver(self.config, status_label))

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
            "Set VIPTALK_BOT_TOKEN plus VIPTALK_OPS_ROOM_ID and/or VIPTALK_DOWN_ROOM_IDS "
            "in secrets.env on this host.")
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
