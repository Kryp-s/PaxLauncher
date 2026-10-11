import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Необов'язковий лічильник онлайну. Працює лише якщо вказано URL (ключ "onlineUrl" у launcher.properties або URL нижче). */
final class Online {
    /** Вкажіть адресу сервера лічильника тут, якщо потрібно. Порожньо = функція вимкнена. */
    private static final String DEFAULT_URL = "";
    private static volatile String url = DEFAULT_URL;

    private final Settings cfg;
    private final BooleanSupplier enabled;
    private final Consumer<Integer> onCount;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ScheduledExecutorService ex;
    private String id;

    Online(Settings cfg, BooleanSupplier enabled, Consumer<Integer> onCount) {
        this.cfg = cfg;
        this.enabled = enabled;
        this.onCount = onCount;
        String u = cfg.get("onlineUrl", DEFAULT_URL).trim();
        if (!u.isEmpty()) url = u;
    }

    static boolean configured() { return url != null && !url.isEmpty(); }

    void start() {
        if (!configured()) return;
        id = cfg.get("onlineId", "");
        if (id.isEmpty()) { id = UUID.randomUUID().toString(); cfg.set("onlineId", id); }
        ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "online-ping");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(this::ping, 0, 60, TimeUnit.SECONDS);
    }

    private void ping() {
        try {
            String q = url + (url.contains("?") ? "&" : "?") + "id=" + id + "&on=" + (enabled.getAsBoolean() ? 1 : 0);
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(q)).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String b = r.body().trim();
            int n = Integer.parseInt(b.replaceAll("[^0-9]", ""));
            onCount.accept(n);
        } catch (Throwable ignored) { }
    }

    void bye() {
        if (!configured() || id == null) return;
        try {
            if (ex != null) ex.shutdownNow();
            String q = url + (url.contains("?") ? "&" : "?") + "id=" + id + "&bye=1";
            http.send(HttpRequest.newBuilder(URI.create(q)).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
        } catch (Throwable ignored) { }
    }
}
