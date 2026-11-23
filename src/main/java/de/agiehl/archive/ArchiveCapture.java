package de.agiehl.archive;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

final class ArchiveCapture {
    private static final int MAX_RESOURCES = 200;
    private static final long MAX_FILE = 32L << 20;
    private static final long MAX_TOTAL = 128L << 20;
    private static final Pattern EXTENSION = Pattern.compile("\\.([a-z0-9]{1,8})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([\\w.:-]+)");
    private final long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
    private final HttpClient client;
    private final Path directory;
    private final Map<URI, String> locations = new HashMap<>();
    private final StringBuilder manifest = new StringBuilder("Status\tURL\tDatei\tDetails\n");
    private long remaining = MAX_TOTAL;
    private int requests;

    ArchiveCapture(HttpClient client, Path directory) {
        this.client = client;
        this.directory = directory;
    }

    void write(URI article) throws IOException, InterruptedException {
        URI root = withoutFragment(article);
        HttpResponse<byte[]> response = fetch(root);
        Files.createDirectory(directory.resolve("assets"));
        String type = contentType(response);
        boolean html = isHtml(type) || type.isBlank() && looksLikeHtml(response.body());
        String original = html ? "source.html.txt" : "original" + suffix(response, "");
        Files.write(directory.resolve(original), response.body());
        String entry = html ? "index.html" : original;
        locations.put(root, entry);
        locations.put(response.uri(), entry);
        record("ORIGINAL", article, original, type + " " + response.uri());
        if (html) {
            new OfflinePage(this, "index.html").write(response);
        } else {
            Document index = Jsoup.parse("");
            index.title("Archiviertes Dokument");
            index.body().appendElement("a").attr("href", original).text("Archiviertes Dokument öffnen");
            Files.writeString(directory.resolve("index.html"), index.outerHtml());
        }
        Files.writeString(directory.resolve("manifest.tsv"), manifest);
    }

    String resource(URI base, String value, String from, String hint) throws IOException, InterruptedException {
        URI uri = resolve(base, value);
        if (uri == null || value.strip().startsWith("#")) {
            return value;
        }
        URI key = withoutFragment(uri);
        if (!locations.containsKey(key)) {
            captureResource(key, hint);
        }
        String name = locations.get(key);
        if (name == null) {
            return uri.toASCIIString();
        }
        Path origin = directory.resolve(from).getParent();
        String relative = origin.relativize(directory.resolve(name)).toString().replace('\\', '/');
        return relative + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
    }

    private void captureResource(URI key, String hint) throws IOException, InterruptedException {
        locations.put(key, null);
        if (requests >= MAX_RESOURCES) {
            record("FEHLT", key, "", "Ressourcenlimit");
            return;
        }
        int number = ++requests;
        HttpResponse<byte[]> response;
        try {
            response = fetch(key);
        } catch (IOException failure) {
            record("FEHLT", key, "", failure.toString());
            return;
        }
        String extension = suffix(response, hint);
        String name = "assets/" + number + extension;
        locations.put(key, name);
        record("OK", key, name, contentType(response) + " " + response.uri());
        if (extension.equals(".css")) {
            String raw = new String(response.body(), charset(response));
            String css = new OfflinePage(this, name).css(response.uri(), raw)
                    .replaceFirst("(?i)^\\uFEFF?@charset\\s+[\"'][^\"']+[\"']\\s*;", "");
            writeText(name, "@charset \"UTF-8\";\n" + css);
        } else if (extension.equals(".html")) {
            new OfflinePage(this, name).write(response);
        } else {
            Files.write(directory.resolve(name), response.body());
        }
    }

    void writeText(String name, String text) throws IOException {
        Files.writeString(directory.resolve(name), text);
    }

    private HttpResponse<byte[]> fetch(URI uri) throws IOException, InterruptedException {
        long time = Math.min(deadline - System.nanoTime(), Duration.ofSeconds(15).toNanos());
        if (time <= 0 || remaining <= 0) {
            throw new IOException("Zeit- oder Größenlimit des Archivs erreicht");
        }
        if (!isWeb(uri)) {
            throw new IOException("Ungültige HTTP-Adresse");
        }
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(time))
                .header("User-Agent", "Schulnachrichten-Archiv/1.0").GET().build();
        var handler = HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),
                Math.min(MAX_FILE, remaining));
        var call = client.sendAsync(request, handler);
        try {
            var response = call.get(time, TimeUnit.NANOSECONDS);
            remaining -= response.body().length;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode() + " für " + uri);
            }
            return response;
        } catch (TimeoutException timeout) {
            throw new IOException("Zeitlimit für " + uri, timeout);
        } catch (ExecutionException error) {
            throw new IOException("Abruf fehlgeschlagen: " + error.getCause(), error.getCause());
        } finally {
            if (!call.isDone()) {
                call.cancel(true);
            }
        }
    }

    static URI resolve(URI base, String value) {
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            URI uri = trimmed.startsWith("?")
                    ? URI.create(base.toASCIIString().split("[?#]", 2)[0] + trimmed.replace(" ", "%20"))
                    : base.resolve(trimmed.replace(" ", "%20")).normalize();
            return isWeb(uri) ? uri : null;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static boolean isWeb(URI uri) {
        return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private static URI withoutFragment(URI uri) {
        return URI.create(uri.toASCIIString().split("#", 2)[0]);
    }

    private static boolean looksLikeHtml(byte[] bytes) {
        String start = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8)
                .stripLeading().toLowerCase(Locale.ROOT);
        return start.startsWith("<!doctype html") || start.startsWith("<html");
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0]
                .strip().toLowerCase(Locale.ROOT);
    }

    static String declaredCharset(HttpResponse<?> response) {
        var match = CHARSET.matcher(response.headers().firstValue("Content-Type").orElse(""));
        if (match.find()) {
            try {
                return Charset.forName(match.group(1)).name();
            } catch (IllegalArgumentException _) {
                return null;
            }
        }
        return null;
    }

    private static Charset charset(HttpResponse<?> response) {
        String name = declaredCharset(response);
        return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
    }

    private static boolean isHtml(String type) {
        return type.equals("text/html") || type.equals("application/xhtml+xml");
    }

    private static String suffix(HttpResponse<?> response, String hint) {
        return switch (contentType(response)) {
            case "text/html", "application/xhtml+xml" -> ".html";
            case "text/css" -> ".css";
            case "text/javascript", "application/javascript" -> ".js";
            case "application/pdf" -> ".pdf";
            case "image/svg+xml" -> ".svg";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/avif" -> ".avif";
            default -> {
                var extension = EXTENSION.matcher(response.uri().getPath());
                yield !hint.isBlank() ? hint : extension.find()
                        ? "." + extension.group(1).toLowerCase(Locale.ROOT) : ".bin";
            }
        };
    }

    private void record(String status, URI uri, String file, String details) {
        manifest.append(status).append('\t').append(uri.toASCIIString()).append('\t').append(file)
                .append('\t').append(details.replaceAll("[\\t\\r\\n]", " ")).append('\n');
    }
}
