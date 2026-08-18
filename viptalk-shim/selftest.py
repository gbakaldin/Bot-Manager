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
  * a garbage body and an unexpected request path => still delivered;
  * VipTalk UNREACHABLE (connection refused) => 502 — a different branch from a
    rejection, and the likelier one during a real incident;
  * one register accepted and the other rejected => 502, every register still
    attempted, and the already-delivered copy re-sent on Alertmanager's retry;
  * a token with no rooms anywhere => disabled, like a missing token;
  * comma-separated and whitespace-padded room lists;
  * a blank instance label => no bracket segment at all;
  * the ops room listed twice => both registers land in it (no dedupe here,
    unlike the app's AlertRouter);
  * a non-numeric port/timeout => Config() raises before the server binds, which
    under `restart: unless-stopped` is a crash loop;
  * an unknown GET path => 404 with a hint.

What is NOT covered: the real VipTalk API (Phase 0 verified the {"text",
"roomIds"} JSON body returns 200 against the live service — see
docs/reviews/VIPTALK_ALERTING_V2/spike.md), an end-to-end POST from a real
Alertmanager over the compose network, and Alertmanager's own retry behaviour.
Those three are host facts; see docs/reviews/VIPTALK_ALERTING_V2/qa.md.

Run by the Maven build via bot-app's VipTalkShimSelfTestRunnerTest, so a change
to shim.py that breaks the outage path fails the same build as a Java change.
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

    def __init__(self, status=200, statuses=None):
        """`statuses` gives a per-request status sequence, for partial-failure cases."""
        self.status = status
        self.statuses = list(statuses) if statuses else None
        self.requests = []
        outer = self

        def next_status():
            if outer.statuses:
                return outer.statuses.pop(0)
            return outer.status

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
                self.send_response(next_status())
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, fmt, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        # poll_interval well below the default 0.5 s: shutdown() waits for the serve
        # loop to notice, and with ~15 servers started and stopped that default alone
        # dominated the suite's runtime.
        self.thread = threading.Thread(
            target=lambda: self.server.serve_forever(poll_interval=0.02), daemon=True)
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
    threading.Thread(target=lambda: server.serve_forever(poll_interval=0.02),
                     daemon=True).start()
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


# --------------------------------------------------------------------------- #
# Added by QA (VIPTALK_ALERTING_V2 Phases 3-6 review). The suite above covers the
# happy path and the two obvious rejections; these cover the rest of the OUTAGE
# path — the branches that only execute when something is already wrong, which is
# the only situation this process ever runs in.
# --------------------------------------------------------------------------- #

def free_port():
    """A port nothing is listening on, for the connection-refused case."""
    import socket
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


def test_viptalk_unreachable_is_retryable():
    print("VipTalk unreachable (connection refused) -> 502, no traceback")
    # A DIFFERENT branch from the rejection case above: urlopen raises URLError, not
    # HTTPError, and it is the likelier one during a real incident — DNS, TLS, a
    # network partition, or VipTalk itself being down at the same time as us.
    env = base_env("http://127.0.0.1:%d" % free_port())
    env["VIPTALK_TIMEOUT_SECONDS"] = "2"
    server, url = start_shim(env)
    try:
        status, _ = post(url, FIRING)
        equal(status, 502, "an unreachable VipTalk is a retryable failure, not a crash")
    finally:
        server.shutdown()


def test_partial_delivery_is_reported_as_failure():
    print("ops accepted, product rooms rejected -> 502 (and the ops copy is already out)")
    viptalk = FakeVipTalk(statuses=[200, 500])
    server, url = start_shim(base_env(viptalk.url))
    try:
        status, _ = post(url, FIRING)
        equal(status, 502, "any register failing makes the whole notification retryable")
        equal(len(viptalk.requests), 2,
              "a failing register does not abort the ones after it")
        # DOCUMENTED CONSEQUENCE, not an assertion of correctness: Alertmanager retries
        # the whole webhook, so the register that DID succeed is delivered twice. A
        # duplicate outage notice is the right trade against a missing one, but it is a
        # real behaviour and belongs in the test rather than in nobody's head.
        check(viptalk.requests[0]["body"]["roomIds"] == ["!ops:matrix-uat.viptalk.org"],
              "the ops copy went out before the failure, so a retry re-sends it")
    finally:
        server.shutdown()
        viptalk.stop()


def test_token_without_rooms_is_disabled():
    print("token set but no rooms anywhere -> disabled, 200, no sends")
    # The other half of `enabled`: the suite above only covered a missing token. A host
    # that filled in the token but no room IDs must behave identically, not post to an
    # empty room list and have VipTalk reject it forever.
    viptalk = FakeVipTalk()
    env = base_env(viptalk.url)
    env["VIPTALK_OPS_ROOM_ID"] = ""
    env["VIPTALK_DOWN_ROOM_IDS"] = ""
    server, url = start_shim(env)
    try:
        status, _ = post(url, FIRING)
        equal(status, 200, "nothing to do is not a failure")
        equal(len(viptalk.requests), 0, "nothing sent")
        _, health = get(url)
        equal(health["enabled"], False, "/health says so, which is how an operator finds out")
    finally:
        server.shutdown()
        viptalk.stop()


def test_room_lists_accept_commas_and_stray_whitespace():
    print("comma-separated and whitespace-padded room lists parse")
    # secrets.env.example says "space- or comma-separated". Only the space form was
    # covered; a deployer following the other half of the sentence must not silently
    # end up with one room ID that is actually three concatenated.
    viptalk = FakeVipTalk()
    env = base_env(viptalk.url)
    env["VIPTALK_DOWN_ROOM_IDS"] = " !a:vt.org, !b:vt.org ,,!c:vt.org "
    env["VIPTALK_OPS_ROOM_ID"] = "  !ops:vt.org  "
    server, url = start_shim(env)
    try:
        post(url, FIRING)
        equal(viptalk.requests[0]["body"]["roomIds"], ["!ops:vt.org"],
              "a padded single room ID is stripped, not sent with spaces")
        equal(viptalk.requests[1]["body"]["roomIds"], ["!a:vt.org", "!b:vt.org", "!c:vt.org"],
              "commas separate, empties are dropped")
    finally:
        server.shutdown()
        viptalk.stop()


def test_no_instance_label_means_no_prefix():
    print("blank instance label -> no bracket segment, never a guess")
    viptalk = FakeVipTalk()
    env = base_env(viptalk.url)
    env["VIPTALK_INSTANCE_LABEL"] = ""
    server, url = start_shim(env)
    try:
        post(url, FIRING)
        check(not viptalk.requests[0]["body"]["text"].startswith("["),
              "an unlabelled instance is unlabelled, not mislabelled (AD-V15)")
        check(viptalk.requests[1]["body"]["text"] == shim.DEFAULT_DOWN_TEXT,
              "the customer copy is then exactly the public_summary wording")
    finally:
        server.shutdown()
        viptalk.stop()


def test_ops_room_listed_twice_gets_both_registers():
    print("ops room also listed as a product room -> it receives BOTH registers")
    # Pinning current behaviour, not endorsing it. AlertRouter in the app dedupes by
    # room so a room can only get one register per alert; the shim has no such step.
    # Misconfiguring VIPTALK_DOWN_ROOM_IDS to include the ops room therefore puts the
    # customer wording into the ops room as well — noisy rather than harmful, and worth
    # knowing before someone does it.
    viptalk = FakeVipTalk()
    env = base_env(viptalk.url)
    env["VIPTALK_DOWN_ROOM_IDS"] = env["VIPTALK_OPS_ROOM_ID"]
    server, url = start_shim(env)
    try:
        post(url, FIRING)
        equal(len(viptalk.requests), 2, "no dedupe: two messages to the same room")
        equal(viptalk.requests[0]["body"]["roomIds"],
              viptalk.requests[1]["body"]["roomIds"], "same room, two registers")
    finally:
        server.shutdown()
        viptalk.stop()


def test_bad_numeric_config_fails_loudly_at_startup():
    print("non-numeric port/timeout -> Config raises rather than starting half-configured")
    # DOCUMENTED SHARP EDGE. Every other malformed input is tolerated on purpose ("a
    # payload-schema change must not drop an outage notice"), but a non-numeric
    # VIPTALK_SHIM_PORT or VIPTALK_TIMEOUT_SECONDS raises out of Config() before the
    # server binds. With restart: unless-stopped that is a crash loop — the container
    # is then down for exactly the outage it exists to report, and the only trace is
    # in `docker compose logs viptalk-shim`. Cheap to make it fall back to the default
    # instead; pinned here so the choice is visible.
    for name in ("VIPTALK_SHIM_PORT", "VIPTALK_TIMEOUT_SECONDS"):
        env = base_env("http://127.0.0.1:1")
        env[name] = "not-a-number"
        try:
            shim.Config(env)
            check(False, "%s='not-a-number' is accepted (fallback to default)" % name)
        except ValueError:
            check(True, "%s='not-a-number' raises ValueError at startup (crash loop)" % name)


def test_unknown_get_path_is_404():
    print("GET on an unexpected path -> 404 with a hint, not a stack trace")
    viptalk = FakeVipTalk()
    server, url = start_shim(base_env(viptalk.url))
    try:
        try:
            get(url, "/metrics")
            check(False, "expected 404")
        except urllib.error.HTTPError as error:
            equal(error.code, 404, "unknown GET path answers 404")
            check("health" in error.read().decode("utf-8"),
                  "the 404 body says where to look instead")
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
                 test_health_masks_the_token,
                 test_viptalk_unreachable_is_retryable,
                 test_partial_delivery_is_reported_as_failure,
                 test_token_without_rooms_is_disabled,
                 test_room_lists_accept_commas_and_stray_whitespace,
                 test_no_instance_label_means_no_prefix,
                 test_ops_room_listed_twice_gets_both_registers,
                 test_bad_numeric_config_fails_loudly_at_startup,
                 test_unknown_get_path_is_404):
        test()
    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
