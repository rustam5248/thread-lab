# Thread Lab: Concurrency Model for Spring MVC on Tomcat

A hands-on lab that makes the Spring MVC + Tomcat threading model visible.
You will run seven small experiments, predict each result first, measure it,
and explain it.

**The one rule behind everything:** in classic Spring MVC, one in-flight request
occupies one Tomcat worker thread from dispatch until the response is written.

---

## 1. Goal of the task

Prove with your own measurements that:

1. A blocked request holds a Tomcat worker thread (Experiments A, B).
2. Saturation travels backwards: worker pool → Tomcat connection queue → OS backlog → client error (C).
3. The real limit is often a downstream pool, such as the HikariCP connection pool (D).
4. Singleton beans are shared by all threads, so mutable fields are bugs (E).
5. Async MVC and virtual threads move the limit; they do not remove it (F, G).

**What you hand in:** this repo, the filled results table (section 6), two thread
dumps, and the end-to-end diagram redrawn from memory.

---

## 2. Prerequisites

| Tool | Why | Check |
| --- | --- | --- |
| JDK 21 or newer | Spring Boot 4 + virtual threads; also runs the load tools | `java -version` |
| Maven 3.9+ (or your IDE) | Builds and runs the app | `mvn -version` |
| curl | Single manual requests | `curl --version` |

No load-testing tool to install: `load/LoadTest.java` and `load/WatchMetrics.java`
run directly with `java` (single-file source launch, JDK only).
If you prefer `hey`, `oha` or k6, use the same concurrency (`-c`) and request counts (`-n`).

---

## 3. What is in the project and why

| File | Role | Why it is used |
| --- | --- | --- |
| `pom.xml` | Spring Boot 4.1, `spring-boot-starter-webmvc`, actuator, jdbc, H2 | Gives embedded Tomcat 11 and Spring MVC: the exact stack under study |
| `application.yml` | Tiny limits: 10 workers, 20 connections, backlog 5, DB pool 5 | Makes every saturation point reachable from a laptop |
| `application.yml` → `management.server.port: 8081` | Actuator on its own connector and thread pool | Metrics stay readable while port 8080 is saturated (a small **bulkhead**) |
| `ThreadLabController` `/api/thread` | Returns the current thread | Shows which thread runs your code |
| `/api/slow?ms=2000` | `Thread.sleep` | Stands in for any blocking I/O: slow REST call, slow query, file read |
| `/api/fast` | Does nothing | The victim: if it gets slow, the cause is queueing, not code |
| `/api/db` → `OrderLookup` | Holds a DB connection for 1 s | Shows the bottleneck moving from Tomcat to HikariCP |
| `/api/counter/unsafe`, `/safe`, `/counter`, `/counter/reset` | `long++` vs `AtomicLong` | Shows why singleton beans need thread-safe state |
| `/api/async/callable` | Returns `Callable` | Worker is released, but the blocking moves to `applicationTaskExecutor` |
| `/api/async/deferred` | Returns `DeferredResult` completed by another thread | Worker is released; an "event" thread finishes the request |
| `load/LoadTest.java` | Concurrent HTTP client: throughput, p50/p95/p99, errors | Generates load and measures it |
| `load/WatchMetrics.java` | Prints busy workers, connections, DB pool usage every second | Lets you watch saturation as it happens |
| Log pattern `[%thread]` | Thread name on every log line | Follow one request across threads |

---

## 4. Start the lab

```bash
mvn spring-boot:run
```

Wait for `Tomcat started on port 8080` and `Tomcat started on port 8081`.
Open three terminals in the project folder:

| Terminal | Command | Purpose |
| --- | --- | --- |
| T1 | `mvn spring-boot:run` | The application and its logs |
| T2 | `java load/WatchMetrics.java` | Live gauges from port 8081 |
| T3 | load tests and `curl` | Your experiments |

Smoke test:

```bash
curl localhost:8080/api/thread
# {"thread":"Thread[#45,http-nio-8080-exec-1,5,main]","virtual":false}
```

**Rule for every experiment:** write your prediction in the results table *before*
running it. A wrong prediction you can explain is better evidence than a lucky guess.

**After changing `application.yml`:** stop T1 (Ctrl+C) and start it again.

---

## 5. Experiments

### A. See the threads that exist (rule 1)

```bash
for i in 1 2 3 4 5 6 7 8 9 10; do curl -s localhost:8080/api/thread; echo; done
jcmd $(jps | grep ThreadLab | cut -d' ' -f1) Thread.print | grep '"http-nio'
```

Or use `curl localhost:8081/actuator/threaddump` and search for `http-nio-8080`.

**Expect:** names like `http-nio-8080-exec-3` repeat (workers are reused).
In the dump: one `http-nio-8080-Acceptor`, one `http-nio-8080-Poller`, and a few
`exec-N` threads parked in the pool queue.

**Why:** only the Acceptor and Poller handle sockets; all request work runs on `exec` workers.

---

### B. Saturate the worker pool (rules 2 and 4)

Predict with Little's Law: throughput ≈ workers ÷ response time = 10 ÷ 2 s = **5 req/s**.

```bash
# T3
java load/LoadTest.java "http://localhost:8080/api/slow?ms=2000" -c 15 -n 75
# while it runs, in a 4th terminal or quickly in T3:
time curl localhost:8080/api/fast
```

**Expect:**
- LoadTest: about 5 req/s, p95 near 4 s (2 s of work + up to 2 s waiting for a worker).
- WatchMetrics: `busy` stuck at 10.
- `/api/fast` takes up to about 2 s although it does nothing. CPU stays low.

**Why:** this is the classic incident. The fast endpoint waits in Tomcat's queue for a free worker.

> Why `-c 15` and not more? The load clients keep their connections open (keep-alive).
> With `max-connections: 20`, more than about 19 clients would also fill the connection
> limit and your `curl` would wait for a connection instead of a worker. That is
> Experiment C, so keep the two effects separate.

---

### C. Overflow the queues (rule 2)

Predict for 60 simultaneous 3-second requests with a 7-second client timeout:
10 run at once and finish at about 3 s, 10 more wait inside Tomcat
(`max-connections: 20`) and finish at about 6 s. Everything else waits in the OS backlog
(5) or cannot connect, and times out at 7 s. Expect **about 20 successes and 40 failures**.

```bash
java load/LoadTest.java "http://localhost:8080/api/slow?ms=3000" -c 60 -n 60 -t 7
```

During the run take a dump: `curl -s localhost:8081/actuator/threaddump > dump-c.json`
and count the `http-nio-8080-exec` threads in `TIMED_WAITING` (sleeping in `Sleep.ms`).

**Expect:** outcomes like `{HTTP 200=20, error HttpConnectTimeoutException=…, error HttpTimeoutException=…}`.
Exact numbers vary: the OS may round the backlog, and Linux and macOS treat a full
backlog differently (silent drop vs reset).

**Why:** `accept-count` is the *last* queue, not the first one. Requests wait for
workers inside Tomcat up to `max-connections`, and only then does the OS backlog fill.

---

### D. The bottleneck moves to the DB pool (rule 5)

Change `application.yml`: `threads.max: 200`, `max-connections: 200`. Restart.
Tomcat now has plenty of workers and connections.

Predict: 5 DB connections ÷ 1 s per request = **about 5 req/s**, regardless of worker count.
With 40 clients a request waits about 7 s for a connection, but `connection-timeout` is 3 s.

```bash
java load/LoadTest.java http://localhost:8080/api/db -c 40 -n 200
```

**Expect:**
- About 5 successful req/s and many `HTTP 500`.
- WatchMetrics: `db.active` = 5, `db.pending` around 35, `busy` around 40.
- T1 log: `SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 3000ms`.

**Why:** more Tomcat threads only created more threads waiting in `getConnection()`.
Throughput is capped by the smallest pool on the request path.
See HikariCP's pool-sizing article in the sources: a small DB pool is usually right; protect it instead of growing it.

---

### E. Singleton thread safety (rule 6)

Restore `threads.max: 10` and `max-connections: 20`. Restart.

```bash
curl localhost:8080/api/counter/reset
java load/LoadTest.java http://localhost:8080/api/counter/unsafe -c 15 -n 10000
java load/LoadTest.java http://localhost:8080/api/counter/safe   -c 15 -n 10000
curl localhost:8080/api/counter
```

**Expect:** `safe` = 10000 exactly, `unsafe` < 10000 (repeat if you get lucky).

**Why:** `++unsafeCounter` is read, add, write. Ten workers run it on the *same*
controller instance, so increments overwrite each other.
Fixes: `AtomicLong`, a lock, or no shared mutable state at all.

---

### F. Async MVC frees the worker (rule 7)

Keep `threads.max: 10`.

1. Single request, watch T1 logs:
   ```bash
   curl localhost:8080/api/async/deferred
   # started on http-nio-8080-exec-2, completed by event-timer
   ```
   The log shows `deferred: worker is being released` on an `exec` thread, and
   `completing the result` on `event-timer` 2 s later.
2. Load test both async styles and compare with Experiment B:
   ```bash
   java load/LoadTest.java http://localhost:8080/api/async/deferred -c 15 -n 75
   java load/LoadTest.java http://localhost:8080/api/async/callable -c 15 -n 60
   ```

**Expect:**
- `deferred`: about 7.5 req/s (15 clients ÷ 2 s). This beats the 5 req/s ceiling of
  10 blocking workers, and `busy` stays near 0. Now only the number of clients limits
  throughput; the next cap would be `max-connections` (20), because each waiting
  request still holds an open connection.
- `callable`: about 4 req/s, slower than plain blocking. The blocking moved to Spring Boot's
  `applicationTaskExecutor`, which uses 8 threads by default (`task-1` … `task-8` in a thread dump).

**Why:** `DeferredResult` helps when *something else* produces the result (a message,
an event, a non-blocking client). `Callable` only moves the blocking to another pool.
Tune that pool with `spring.task.execution.pool.*` if you rely on it.

---

### G. Virtual threads (rule 8)

Set `spring.threads.virtual.enabled: true`, keep `threads.max: 10`, restart.

1. `curl localhost:8080/api/thread` now returns `"virtual": true` and a
   `VirtualThread[#…]` name.
2. Repeat B: `java load/LoadTest.java "http://localhost:8080/api/slow?ms=2000" -c 15 -n 75`.
   **Expect** about 7.5 req/s (15 clients ÷ 2 s): `threads.max` no longer limits, because a
   sleeping virtual thread releases its carrier; only the client count does. `/api/fast` stays fast.
3. Set `max-connections: 200` as well, restart, and repeat D with `-c 40`.
   **Expect** the same ~5 req/s and the same Hikari timeouts. The DB pool is still the limit.
4. Virtual threads are not listed by `Thread.print`. Use:
   ```bash
   jcmd <pid> Thread.dump_to_file -format=json vt-dump.json
   ```
5. Note: `tomcat.threads.busy` is not meaningful with virtual threads, because Tomcat
   then uses a virtual-thread executor instead of its pool. Rely on LoadTest numbers and
   the Hikari gauges.

**Why:** virtual threads make blocking cheap but keep thread-per-request.
Downstream pools still need limits, timeouts and bulkheads.

Finally, set `spring.threads.virtual.enabled: false` again.

---

## 6. Results table (fill it in)

| Exp | Config | Command | Prediction | Measured req/s | p95 ms | Errors | Limit hit | Explanation (rule #) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| B | 10 workers | `/slow` 2 s, c=15 | 5 req/s | | | | | |
| C | 20 conns, backlog 5 | `/slow` 3 s, c=60, t=7 | ~20 OK, ~40 fail | | | | | |
| D | 200 workers, Hikari 5 | `/db`, c=40 | 5 req/s + 500s | | | | | |
| E | 10 workers | counters, c=15, n=10000 | unsafe < 10000 | | | | | |
| F1 | 10 workers | `/async/deferred`, c=15 | 7.5 req/s | | | | | |
| F2 | 10 workers | `/async/callable`, c=15 | 4 req/s | | | | | |
| G1 | virtual threads | `/slow` 2 s, c=15 | 7.5 req/s | | | | | |
| G2 | virtual + 200 conns | `/db`, c=40 | 5 req/s + 500s | | | | | |

Evidence checklist:

- [ ] Results table filled, each row explained with a rule number
- [ ] Thread dump from C showing 10 `exec` threads in `TIMED_WAITING`
- [ ] Log lines from F showing one request started and finished on different threads
- [ ] Diagram of the end-to-end model redrawn from memory, with each limit marked
- [ ] One-page recommendation for a service where one slow dependency froze everything
      (timeouts, pool sizing, bulkheads, whether virtual threads fit)

---

## 7. Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Startup fails: `minSpareThreads` larger than `maxThreads` | Keep `min-spare` ≤ `threads.max` |
| `Port 8080 already in use` | Another app is running; stop it or change `server.port` |
| `4.1.1` parent not found | Use the latest 4.1.x or 4.0.x Spring Boot version in `pom.xml` |
| WatchMetrics shows `down` | App not started, or wrong port (metrics are on **8081**) |
| WatchMetrics shows `-` for `db.*` | Normal until the first `/api/db` call creates the pool |
| `curl /api/fast` hangs during B | Too many load clients filled `max-connections`; use `-c 15` |
| Numbers differ a little from predictions | Normal: JIT warm-up, OS scheduling. Run each test twice and record the second run |

---

## 8. Theory sources

Required (official docs or primary author):

1. Apache Tomcat 11, HTTP Connector (maxThreads, maxConnections, acceptCount, Connector Comparison):
   https://tomcat.apache.org/tomcat-11.0-doc/config/http.html
2. Spring Framework, DispatcherServlet: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet.html
   and request processing sequence: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/sequence.html
3. Spring Framework, Asynchronous Requests: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html
4. Spring Boot, Task Execution and Scheduling (applicationTaskExecutor, virtual threads):
   https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html
5. HikariCP, About Pool Sizing: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing

Deeper reading:

- JEP 444, Virtual Threads: https://openjdk.org/jeps/444
- JEP 491, Synchronize Virtual Threads without Pinning: https://openjdk.org/jeps/491
- Spring Boot, Metrics (Tomcat and DataSource metrics): https://docs.spring.io/spring-boot/reference/actuator/metrics.html
- Spring Boot, Embedded Web Servers how-to: https://docs.spring.io/spring-boot/how-to/webserver.html
- Spring Boot, Common Application Properties (search `server.tomcat`): https://docs.spring.io/spring-boot/appendix/application-properties/index.html
- Tomcat 11, Executor: https://tomcat.apache.org/tomcat-11.0-doc/config/executor.html
- Book: *Java Concurrency in Practice*, Brian Goetz et al., chapters 2, 3 and 8
