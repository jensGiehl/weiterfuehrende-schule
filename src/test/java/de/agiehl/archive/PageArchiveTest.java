package de.agiehl.archive;

import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PageArchiveTest {
    private static final String REVISION = "a".repeat(64);
    private final Map<String, String> content = new HashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private URI site;

    @TempDir
    Path data;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                if (!content.containsKey(path)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                String type = switch (path.substring(path.lastIndexOf('.') + 1)) {
                    case "html" -> "text/html; charset=utf-8";
                    case "css" -> "text/css; charset=utf-8";
                    case "pdf" -> "application/pdf";
                    default -> "application/octet-stream";
                };
                byte[] bytes = content.get(path).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        site = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void storesOriginalAndRewritesEmbeddedAssetsForOfflineReading() throws Exception {
        String html = """
                <base href="/material/"><link rel="stylesheet" href="css/site.css">
                <script src="app.js"></script><h1 id="news">Neuigkeiten für Eltern</h1>
                <img data-src="bild.png" srcset="bild.png 1x, gross.png 2x" onload="run()">
                <a href="brief.pdf#page=2">Brief</a><a href="/seite.html">Schule</a>
                <img src="fehlt.png"><iframe src="innen.html"></iframe>
                """;
        content.put("/news.html", html);
        content.put("/material/css/site.css", "@import 'child.css'; body {background:url(../bild.png)}");
        content.put("/material/css/child.css", "p {background:url(../gross.png)}");
        content.put("/material/innen.html", "<img src='bild.png'>");
        content.put("/material/app.js", "run()");
        content.put("/material/bild.png", "klein");
        content.put("/material/gross.png", "groß");
        content.put("/material/brief.pdf", "Briefinhalt");
        PageArchive archive = new PageArchive(data);
        archive.save("SCHULE", site.resolve("/news.html#news"), REVISION);
        Path root = data.resolve("archive/SCHULE").resolve(REVISION);
        String manifest = Files.readString(root.resolve("manifest.tsv"));
        assertEquals(html, Files.readString(root.resolve("source.html.txt")));
        var page = Jsoup.parse(Files.readString(root.resolve("index.html")));
        assertEquals("Neuigkeiten für Eltern", page.selectFirst("h1").text());
        assertTrue(page.select("script, [onload], base").isEmpty());
        assertEquals("klein", Files.readString(root.resolve(page.selectFirst("img").attr("src"))));
        String srcset = page.selectFirst("img").attr("srcset");
        assertEquals("groß", Files.readString(root.resolve(srcset.split(", ")[1].split(" ")[0])));
        Path cssPath = root.resolve(page.selectFirst("link").attr("href"));
        String css = Files.readString(cssPath);
        String imageName = Path.of(page.selectFirst("img").attr("src")).getFileName().toString();
        assertTrue(css.contains("url(\"" + imageName + "\")"));
        String imported = css.substring(css.indexOf("@import \"") + 9).split("\"", 2)[0];
        assertTrue(Files.readString(cssPath.resolveSibling(imported)).contains("url(\""));
        assertEquals("Briefinhalt", Files.readString(root.resolve(page.selectFirst("a").attr("href").split("#")[0])));
        assertTrue(page.selectFirst("a").attr("href").endsWith("#page=2"));
        assertEquals(site.resolve("/seite.html").toString(), page.select("a").get(1).attr("href"));
        Path frame = root.resolve(page.selectFirst("iframe").attr("src"));
        var framePage = Jsoup.parse(Files.readString(frame));
        assertEquals("klein", Files.readString(frame.getParent().resolve(framePage.selectFirst("img").attr("src"))));
        assertTrue(manifest.contains("FEHLT\t" + site.resolve("/material/fehlt.png")));
        assertTrue(manifest.contains("/material/app.js\t"));
        int initial = requests.get();
        archive.save("SCHULE", site.resolve("/news.html#news"), REVISION);
        assertEquals(initial, requests.get());
    }

    @Test
    void retriesFailedArticleAndStoresDirectPdf() throws Exception {
        PageArchive archive = new PageArchive(data);
        URI article = site.resolve("/elternbrief.pdf");
        Path target = data.resolve("archive/SCHULE").resolve(REVISION);
        assertThrows(IOException.class, () -> archive.save("SCHULE", article, REVISION));
        assertFalse(Files.exists(target));
        content.put("/elternbrief.pdf", "Elternbrief");
        archive.save("SCHULE", article, REVISION);
        assertEquals("Elternbrief", Files.readString(target.resolve("original.pdf")));
        assertTrue(Files.readString(target.resolve("index.html")).contains("original.pdf"));
    }

    @Test
    void concurrentWritesPublishOneCompleteRevision() throws Exception {
        content.put("/news.html", "<p>Neue Meldung</p>");
        PageArchive archive = new PageArchive(data);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = workers.submit(() -> {
                archive.save("SCHULE", site.resolve("/news.html"), REVISION);
                return null;
            });
            var two = workers.submit(() -> {
                archive.save("SCHULE", site.resolve("/news.html"), REVISION);
                return null;
            });
            one.get();
            two.get();
        }
        Path school = data.resolve("archive/SCHULE");
        assertTrue(Files.readString(school.resolve(REVISION).resolve("index.html")).contains("Neue Meldung"));
        try (var entries = Files.list(school)) {
            assertEquals(1, entries.count());
        }
    }
}
