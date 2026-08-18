#!/usr/bin/env python3
"""Self-test for shim.py. No network, no containers, no dependencies.

    python3 viptalk-shim/selftest.py

The shim sits on a path that only executes during an outage, which is the worst
possible place for an untested bug: nobody is watching it succeed, and the one
time it runs is the one time it must not fail. So it gets a test, and the test
runs a REAL HTTP round trip — Alertmanager's webhook POST goes over a socket into
the shim, and the shim's call goes over a socket into a stub standing in for
VipTalk — rather than only calling functions directly.

What is covered here:
  * the happy path end to end: webhook in, two VipTalk POSTs out, 200 back;
  * the exact JSON body VipTalk is given ({"text": …, "roomIds": [ … ]}) and the
    URL the token is placed in;
  * the two registers going to their own rooms with their own wording;
  * the instance-label stamp;
  * VipTalk failing => 502, so Alertmanager retries;
  * an unconfigured shim => 200 and no sends (a non-prod instance must not retry
    forever over something that can never succeed);
  * a `resolved` payload => no send (the recovery half belongs to the app path);
  * a garbage body and an unexpected request path => still delivered.

What is NOT covered: the real VipTalk API (Phase 0 verified the {"text",
"roomIds"} JSON body returns 200 against the live service — see
docs/reviews/VIPTALK_ALERTING_V2/spike.md), and Alertmanager's own retry
behaviour.
"""

import json
import os
import sys
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

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
# A stub standing in for api.viptalk.org.
# --------------------------------------------------------------------------- #

class FakeVipTalk(object):

    def __init__(self, status=200):
        self.status = status
        self.requests = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length)
                outer.requests.append({
                    "path": self.path,
                    "contentType": self.headers.get("Content-Type"),
                    "body": json.loads(raw.decode("utf-8")),
                })
                body = b'{"message":"ok"}'
                self.send_response(outer.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, fmt, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return "http://127.0.0.1:%d" % self.server.server_address[1]

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


def start_shim(env):
    config = shim.Config(env)
    server = shim.serve(config)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, "http://127.0.0.1:%d" % server.server_address[1]


def post(url, body, path="/alertmanager"):
    """Returns the response status, treating an error status as a value not a raise."""
    request = urllib.request.Request(
        url + path, data=body, method="POST",
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8"))


def get(url, path="/health"):
    with urllib.request.urlopen(url + path, timeout=5) as response:
        return response.status, json.loads(response.read().decode("utf-8"))


FIRING = json.dumps({
    "version": "4",
    "status": "firing",
    "groupLabels": {"alertname": "BotManagerDown"},
    "alerts": [{
        "status": "firing",
        "labels": {"alertname": "BotManagerDown", "severity": "critical", "audience": "both"},
        "annotations": {"summary": "bot-manager is not scrapeable"},
    }],
}).encode("utf-8")

RESOLVED = json.dumps({
    "version": "4",
    "status": "resolved",
    "alerts": [{"status": "resolved", "labels": {"alertname": "BotManagerDown"}}],
}).encode("utf-8")


def base_env(viptalk_url):
    return {
        "VIPTALK_BASE_URL": viptalk_url,
        "VIPTALK_BOT_TOKEN": "tok-abcdef123456",
        "VIPTALK_OPS_ROOM_ID": "!ops:matrix-uat.viptalk.org",
        "VIPTALK_DOWN_ROOM_IDS": "!tip:matrix-uat.viptalk.org !bom:matrix-uat.viptalk.org",
        "VIPTALK_INSTANCE_LABEL": "prod",
        "VIPTALK_SHIM_PORT": "0",
    }


# --------------------------------------------------------------------------- #

def test_happy_path():
    print("firing webhook, fully configured -> both registers delivered")
    viptalk = FakeVipTalk()
    server, url = start_shim(base_env(viptalk.url))
    try:
        status, _ = post(url, FIRING)
        equal(status, 200, "shim answers 200 when VipTalk accepted everything")
        equal(len(viptalk.requests), 2, "one POST per register, not one per room (AD-4)")

        for request in viptalk.requests:
            equal(request["path"], "/v1/bot/tok-abcdef123456/sendMessage",
                  "token goes in the URL path")
            equal(request["contentType"], "application/json", "JSON body, per the Phase 0 spike")
            check(sorted(request["body"].keys()) == ["roomIds", "text"],
                  "body is exactly {text, roomIds}")

        technical, customer = viptalk.requests[0]["body"], viptalk.requests[1]["body"]
        equal(technical["roomIds"], ["!ops:matrix-uat.viptalk.org"],
              "technical register goes to the ops room")
        equal(customer["roomIds"],
              ["!tip:matrix-uat.viptalk.org", "!bom:matrix-uat.viptalk.org"],
              "customer register goes to the product rooms")
        check("not scrapeable" in technical["text"], "ops room gets the technical wording")
        check(customer["text"].endswith(shim.DEFAULT_DOWN_TEXT),
              "product rooms get the customer wording verbatim")
        check("not scrapeable" not in customer["text"],
              "no technical wording leaks into a product room")
        check(technical["text"].startswith("[prod] ") and customer["text"].startswith("[prod] "),
              "both registers carry the instance label (AD-V15)")
    finally:
        server.shutdown()
        viptalk.stop()


def test_viptalk_failure_is_retryable():
    print("VipTalk rejecting -> 502 so Alertmanager retries")
    viptalk = FakeVipTalk(status=500)
    server, url = start_shim(base_env(viptalk.url))
    try:
        status, _ = post(url, FIRING)
        equal(status, 502, "shim answers 502 on a VipTalk failure (AD-8)")
        equal(len(viptalk.requests), 2, "every register is still attempted")
    finally:
        server.shutdown()
        viptalk.stop()


def test_unconfigured_is_a_quiet_no_op():
    print("no token -> 200 and no sends")
    viptalk = FakeVipTalk()
    env = base_env(viptalk.url)
    env["VIPTALK_BOT_TOKEN"] = ""
    server, url = start_shim(env)
    try:
        status, _ = post(url, FIRING)
        equal(status, 200, "no retry storm against config that can never succeed")
        equal(len(viptalk.requests), 0, "nothing sent")
    finally:
        server.shutdown()
        viptalk.stop()


def test_no_product_rooms_still_serves_ops():
    print("staging posture (ops room only, no product rooms) -> technical register only")
    viptalk = FakeVipTalk()
    env = base_env(viptalk.url)
    env["VIPTALK_DOWN_ROOM_IDS"] = ""
    env["VIPTALK_INSTANCE_LABEL"] = "staging"
    server, url = start_shim(env)
    try:
        status, _ = post(url, FIRING)
        equal(status, 200, "delivered")
        equal(len(viptalk.requests), 1, "only the technical register has rooms")
        check(viptalk.requests[0]["body"]["text"].startswith("[staging] "),
              "staging is labelled as staging in the shared ops room")
        check(shim.DEFAULT_DOWN_TEXT not in viptalk.requests[0]["body"]["text"],
              "customer copy never reaches a non-prod instance's audience (AD-V7)")
    finally:
        server.shutdown()
        viptalk.stop()


def test_resolved_payload_is_not_published():
    print("resolved payload -> nothing sent (recovery belongs to the app path)")
    viptalk = FakeVipTalk()
    server, url = start_shim(base_env(viptalk.url))
    try:
        status, _ = post(url, RESOLVED)
        equal(status, 200, "accepted")
        equal(len(viptalk.requests), 0, "the static outage text is never sent on recovery")
    finally:
        server.shutdown()
        viptalk.stop()


def test_malformed_input_still_delivers():
    print("garbage body and an unexpected path -> still delivered")
    viptalk = FakeVipTalk()
    server, url = start_shim(base_env(viptalk.url))
    try:
        status, _ = post(url, b"not json at all", path="/somewhere/else")
        equal(status, 200, "a payload-schema change is not a reason to drop an outage notice")
        equal(len(viptalk.requests), 2, "both registers still delivered")
    finally:
        server.shutdown()
        viptalk.stop()


def test_health_masks_the_token():
    print("GET /health -> config summary with the token masked")
    viptalk = FakeVipTalk()
    server, url = start_shim(base_env(viptalk.url))
    try:
        status, body = get(url)
        equal(status, 200, "health answers 200")
        equal(body["enabled"], True, "reports itself enabled")
        equal(body["opsRooms"], 1, "reports the ops room count")
        equal(body["productRooms"], 2, "reports the product room count")
        check("tok-abcdef123456" not in json.dumps(body), "the token is never echoed in full")
    finally:
        server.shutdown()
        viptalk.stop()


def main():
    for test in (test_happy_path,
                 test_viptalk_failure_is_retryable,
                 test_unconfigured_is_a_quiet_no_op,
                 test_no_product_rooms_still_serves_ops,
                 test_resolved_payload_is_not_published,
                 test_malformed_input_still_delivers,
                 test_health_masks_the_token):
        test()
    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
