import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prints the key concurrency gauges once per second. JDK only (Java 21+):
 *
 *   java load/WatchMetrics.java            (default http://localhost:8081, the management port)
 *   java load/WatchMetrics.java http://localhost:8081
 *
 * Tomcat meters are filtered to the application connector (http-nio-8080), so the
 * management connector on 8081 is not mixed in. Stop with Ctrl+C. "-" means the meter
 * does not exist yet (Hikari metrics appear after the first database call).
 */
public class WatchMetrics {

    private static final String APP = "?tag=name:http-nio-8080";
    private static final String[][] METERS = {
            {"tomcat.threads.busy" + APP, "busy"},
            {"tomcat.threads.current" + APP, "workers"},
            {"tomcat.connections.current" + APP, "conns"},
            {"hikaricp.connections.active", "db.active"},
            {"hikaricp.connections.pending", "db.pending"},
            {"jvm.threads.live", "jvm.threads"},
    };
    private static final Pattern VALUE = Pattern.compile("\"value\"\\s*:\\s*([-0-9.Ee]+)");

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8081";
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm:ss");

        StringBuilder header = new StringBuilder(String.format("%-9s", "time"));
        for (String[] m : METERS) {
            header.append(String.format("%12s", m[1]));
        }
        System.out.println(header);

        while (true) {
            StringBuilder line = new StringBuilder(String.format("%-9s", LocalTime.now().format(time)));
            for (String[] m : METERS) {
                line.append(String.format("%12s", read(http, base + "/actuator/metrics/" + m[0])));
            }
            System.out.println(line);
            Thread.sleep(1000);
        }
    }

    private static String read(HttpClient http, String url) {
        String value = readOnce(http, url);
        if ("-".equals(value) && url.contains("?tag=")) {
            // tag not found on this setup: fall back to the meter summed over all connectors
            value = readOnce(http, url.substring(0, url.indexOf('?')));
        }
        return value;
    }

    private static String readOnce(HttpClient http, String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return "-";
            }
            Matcher m = VALUE.matcher(r.body());
            return m.find() ? String.valueOf((long) Double.parseDouble(m.group(1))) : "-";
        } catch (Exception e) {
            return "down";
        }
    }
}
