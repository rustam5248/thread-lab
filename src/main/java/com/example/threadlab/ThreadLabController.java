package com.example.threadlab;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * One endpoint per rule of the mental model. This controller is a singleton:
 * every Tomcat worker thread calls the same instance.
 */
@RestController
@RequestMapping("/api")
class ThreadLabController {

    private static final Logger log = LoggerFactory.getLogger(ThreadLabController.class);

    private final OrderLookup orders;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "event-timer"));

    private long unsafeCounter = 0;                          // shared by all threads, NOT thread-safe
    private final AtomicLong safeCounter = new AtomicLong(); // shared by all threads, thread-safe

    ThreadLabController(OrderLookup orders) {
        this.orders = orders;
    }

    /** Rule 1: which thread runs my request? */
    @GetMapping("/thread")
    Map<String, Object> thread() {
        Thread t = Thread.currentThread();
        return Map.of("thread", t.toString(), "virtual", t.isVirtual());
    }

    /** Rule 4: blocking I/O keeps the worker busy while doing nothing. */
    @GetMapping("/slow")
    String slow(@RequestParam(defaultValue = "2000") long ms) {
        log.info("blocking for {} ms", ms);
        Sleep.ms(ms);
        return "slept " + ms + " ms";
    }

    /** The victim: no work at all. If it gets slow, the cause is queueing. */
    @GetMapping("/fast")
    String fast() {
        return "fast";
    }

    /** Rule 5: the limit moves to the connection pool. */
    @GetMapping("/db")
    String db() {
        return orders.slowQuery();
    }

    /** Rule 6: read-modify-write on a shared field loses updates. */
    @GetMapping("/counter/unsafe")
    long unsafe() {
        return ++unsafeCounter;
    }

    @GetMapping("/counter/safe")
    long safe() {
        return safeCounter.incrementAndGet();
    }

    @GetMapping("/counter")
    Map<String, Long> counters() {
        return Map.of("unsafe", unsafeCounter, "safe", safeCounter.get());
    }

    @GetMapping("/counter/reset")
    Map<String, Long> reset() {
        unsafeCounter = 0;
        safeCounter.set(0);
        return counters();
    }

    /** Rule 7a: Callable frees the worker but blocks a thread of applicationTaskExecutor. */
    @GetMapping("/async/callable")
    Callable<String> callable() {
        String start = Thread.currentThread().getName();
        log.info("callable: worker is being released");
        return () -> {
            log.info("callable: running the blocking part");
            Sleep.ms(2000);
            return "started on " + start + ", ran on " + Thread.currentThread().getName();
        };
    }

    /** Rule 7b: DeferredResult frees the worker; another thread (an "event") completes it. */
    @GetMapping("/async/deferred")
    DeferredResult<String> deferred() {
        String start = Thread.currentThread().getName();
        DeferredResult<String> result = new DeferredResult<>(5000L);
        timer.schedule(() -> {
            log.info("deferred: completing the result");
            result.setResult("started on " + start + ", completed by " + Thread.currentThread().getName());
        }, 2, TimeUnit.SECONDS);
        log.info("deferred: worker is being released");
        return result;
    }
}
