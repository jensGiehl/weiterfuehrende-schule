package de.agiehl.sourcenews;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class NewsReader implements AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public List<NewsItem> read(School school) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(school.listingUri())
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "Laura-Schule/1.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || !belongsToSchool(school, response.uri())) {
            throw new IOException("Unerwartete Antwort für %s: HTTP %d, %s"
                    .formatted(school.displayName(), response.statusCode(), response.uri()));
        }
        var document = Jsoup.parse(new ByteArrayInputStream(response.body()), null, response.uri().toString());
        return parse(school, document);
    }

    static List<NewsItem> parse(School school, Document document) throws IOException {
        var items = new LinkedHashSet<NewsItem>();
        for (Element headline : document.select(school.headlineSelector())) {
            var title = headline.text().strip();
            if (title.isEmpty() || headline.attr("href").isBlank() || headline.attr("href").startsWith("#")) {
                continue;
            }
            try {
                var url = URI.create(headline.absUrl("href"));
                if (belongsToSchool(school, url) && !url.equals(school.listingUri())) {
                    items.add(new NewsItem(title, url));
                }
            } catch (IllegalArgumentException ignored) {
                continue;
            }
        }
        if (items.isEmpty() && !(school == School.EDIGHEIM && document.selectFirst(".news .no-news-found") != null)) {
            throw new IOException("Keine gültigen Nachrichtenüberschriften auf " + school.listingUri());
        }
        return List.copyOf(items);
    }

    private static boolean belongsToSchool(School school, URI uri) {
        String host = school.listingUri().getHost();
        return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getUserInfo() == null
                && (host.equalsIgnoreCase(uri.getHost()) || ("www." + host).equalsIgnoreCase(uri.getHost()));
    }

    @Override
    public void close() {
        client.close();
    }
}
