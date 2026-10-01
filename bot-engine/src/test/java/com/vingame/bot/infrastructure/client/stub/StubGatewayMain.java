package com.vingame.bot.infrastructure.client.stub;

import java.util.concurrent.CountDownLatch;

/**
 * Runs {@link StubGateway} stand-alone on a loopback port, for the manual block-handling check
 * against a locally running bot-manager (GATEWAY_REQUEST_BUDGET AD-22, verification V4d). <b>Laptop
 * only — it binds 127.0.0.1 and nothing else.</b>
 * <p>
 * <pre>
 * # from the repo root, after `mvn -pl bot-engine -am test-compile`
 * java -cp "bot-engine/target/test-classes:bot-engine/target/classes:$(cat cp.txt)" \
 *      com.vingame.bot.infrastructure.client.stub.StubGatewayMain 18099
 *
 * curl -X POST http://127.0.0.1:18099/__stub/block     # every gwms path answers the CF page
 * curl -X POST http://127.0.0.1:18099/__stub/unblock   # back to normal answers
 * </pre>
 * ({@code cp.txt} from {@code mvn -pl bot-engine dependency:build-classpath -Dmdep.outputFile=cp.txt}.)
 * Point a local {@code Environment}'s {@code apiGateway} at the printed URL. The stub prints one
 * line per minute with its own sliding-window count — the independent witness the release notes
 * record (V4d: "the stub's per-window max").
 */
public final class StubGatewayMain {

    private StubGatewayMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18099;
        StubGateway stub = StubGateway.startOnPort(port);
        System.out.println("StubGateway listening on " + stub.baseUrl()
                + " — POST /__stub/block | /__stub/unblock; Ctrl-C to stop");
        Runtime.getRuntime().addShutdownHook(new Thread(stub::close));

        Thread.ofVirtual().name("stub-gateway-report").start(() -> {
            int max = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException e) {
                    return;
                }
                int now = stub.countInLastWindow();
                max = Math.max(max, now);
                System.out.println("window=" + now + " max=" + max + " total=" + stub.totalReceived()
                        + " blocked=" + stub.isBlocked() + " paths={" + stub.describe() + "}");
            }
        });
        new CountDownLatch(1).await();
    }
}
