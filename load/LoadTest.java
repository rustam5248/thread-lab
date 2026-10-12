import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Minimal load generator, JDK only (Java 21+). Run without compiling:
 *
 *   java load/LoadTest.java "http://localhost:8080/api/slow?ms=2000" -c 20 -n 100 -t 30
 *
 *   -c  concurrent clients (default 10)
 *   -n  total requests     (default 100)
 *   -t  timeout in seconds for connect and for each request (default 30)
 */
public class LoadTest {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: java load/LoadTest.java <url> [-c clients] [-n requests] [-t timeoutSeconds]");
            return;
        }
        String url = args[0];
        int clients = 10, total = 100, timeout = 30;
        for (int i = 1; i < args.length - 1; i += 2) {
            switch (args[i]) {
                case "-c" -> clients = Integer.parseInt(args[i + 1]);
                case "-n" -> total = Integer.parseInt(args[i + 1]);
                case "-t" -> timeout = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(timeout))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .GET().build();

        AtomicInteger next = new AtomicInteger();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        Map<String, LongAdder> outcomes = new ConcurrentHashMap<>();
        final int totalRequests = total;

        System.out.printf("%s%n  %d clients, %d requests, timeout %ds%n", url, clients, total, timeout);
        long start = System.nanoTime();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < clients; w++) {
            workers.add(Thread.ofPlatform().name("client-" + w).start(() -> {
                while (next.getAndIncrement() < totalRequests) {
                    long t0 = System.nanoTime();
                    String outcome;
                    try {
                        HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
                        outcome = "HTTP " + r.statusCode();
                        if (r.statusCode() < 400) {
                            latencies.add((System.nanoTime() - t0) / 1_000_000);
                        }
                    } catch (Exception e) {
                        outcome = "error " + e.getClass().getSimpleName();
                    }
                    outcomes.computeIfAbsent(outcome, k -> new LongAdder()).increment();
                }
            }));
        }
        for (Thread t : workers) {
            t.join();
        }
        double seconds = (System.nanoTime() - start) / 1e9;

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        System.out.printf("  duration     %.1f s%n", seconds);
        System.out.printf("  throughput   %.1f successful req/s%n", sorted.size() / seconds);
        System.out.println("  outcomes     " + new TreeMap<>(outcomes));
        if (!sorted.isEmpty()) {
            System.out.printf("  latency ms   min %d | p50 %d | p95 %d | p99 %d | max %d%n",
                    sorted.get(0), pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), sorted.get(sorted.size() - 1));
        }
    }

    private static long pct(List<Long> sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
