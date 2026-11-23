package de.agiehl.archive;

import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OfflinePage {
    private static final Pattern CSS_URL = Pattern.compile(
            "(?is)/\\*.*?\\*/|url\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^)]*?))\\s*\\)"
                    + "|@import\\s+[\"']([^\"']+)[\"']");
    private static final Pattern DOCUMENT = Pattern.compile(
            "(?i)\\.(?:pdf|docx?|xlsx?|pptx?|od[tsp]|rtf|txt|zip|csv|ics|epub|jpe?g|png|webp|gif|mp[34]|webm)$");
    private static final Set<String> RESOURCE_LINKS = Set.of("stylesheet", "icon", "apple-touch-icon",
            "preload", "modulepreload", "manifest", "mask-icon");
    private static final String POLICY = "default-src 'none'; img-src 'self' file: data:; "
            + "style-src 'self' file: 'unsafe-inline'; font-src 'self' file: data:; "
            + "media-src 'self' file: data:; frame-src 'self' file:; base-uri 'none'; form-action 'none'";
    private final ArchiveCapture capture;
    private final String file;

    OfflinePage(ArchiveCapture capture, String file) {
        this.capture = capture;
        this.file = file;
    }

    void write(HttpResponse<byte[]> response) throws IOException, InterruptedException {
        Document page = Jsoup.parse(new ByteArrayInputStream(response.body()), ArchiveCapture.declaredCharset(response),
                response.uri().toASCIIString());
        Element baseTag = page.selectFirst("base[href]");
        URI base = baseTag == null ? null : ArchiveCapture.resolve(response.uri(), baseTag.attr("href"));
        if (base == null) {
            base = response.uri();
        }
        page.select("base, meta[http-equiv=refresh], meta[http-equiv=Content-Security-Policy]").remove();
        page.select("meta[http-equiv=Content-Type]").remove();
        for (Element element : page.getAllElements()) {
            rewrite(element, base);
        }
        page.select("script, link[rel=dns-prefetch], link[rel=preconnect]").remove();
        page.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", POLICY);
        page.charset(StandardCharsets.UTF_8);
        page.outputSettings().prettyPrint(false);
        capture.writeText(file, page.outerHtml());
    }

    private void rewrite(Element element, URI base) throws IOException, InterruptedException {
        String tag = element.normalName();
        for (var attribute : element.attributes().asList()) {
            String name = attribute.getKey();
            if (name.startsWith("on") || name.equals("integrity") || name.equals("crossorigin")
                    || name.equals("srcdoc") || name.equals("ping")) {
                element.removeAttr(name);
            }
        }
        if (tag.equals("style")) {
            String rewritten = css(base, element.data());
            element.empty().appendChild(new DataNode(rewritten));
        }
        if (element.hasAttr("style")) {
            element.attr("style", css(base, element.attr("style")));
        }
        if (tag.equals("iframe") || tag.equals("frame")) {
            element.attr("sandbox", "");
        }
        if (tag.equals("object")) {
            localAttribute(element, "data", base, "");
        }
        if (tag.equals("image") || tag.equals("use")) {
            localAttribute(element, "href", base, "");
            localAttribute(element, "xlink:href", base, "");
        }
        localAttribute(element, "src", base, tag.equals("script") ? ".js" : "");
        localAttribute(element, "poster", base, "");
        localAttribute(element, "background", base, "");
        if (element.hasAttr("srcset")) {
            element.attr("srcset", srcset(base, element.attr("srcset")));
        }
        if (tag.equals("img") || tag.equals("source")) {
            for (String attr : List.of("data-src", "data-original", "data-lazy-src")) {
                if (element.hasAttr(attr) && !element.attr(attr).isBlank()) {
                    localAttribute(element, attr, base, "");
                    element.attr("src", element.attr(attr));
                }
            }
            for (String attr : List.of("data-srcset", "data-lazy-srcset")) {
                if (element.hasAttr(attr) && !element.attr(attr).isBlank()) {
                    String rewritten = srcset(base, element.attr(attr));
                    element.attr(attr, rewritten).attr("srcset", rewritten);
                }
            }
        }
        if (tag.equals("link") && element.hasAttr("imagesrcset")) {
            element.attr("imagesrcset", srcset(base, element.attr("imagesrcset")));
        }
        if ((tag.equals("a") || tag.equals("area") || tag.equals("link")) && element.hasAttr("href")) {
            hyperlink(element, base);
        }
    }

    private void hyperlink(Element element, URI base) throws IOException, InterruptedException {
        String value = element.attr("href").strip();
        if (value.startsWith("#")) {
            return;
        }
        URI target = ArchiveCapture.resolve(base, value);
        if (target == null) {
            if (value.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                element.removeAttr("href");
            }
            return;
        }
        var relations = List.of(element.attr("rel").toLowerCase(Locale.ROOT).split("\\s+"));
        boolean resource = element.normalName().equals("link") && relations.stream().anyMatch(RESOURCE_LINKS::contains);
        if (resource || DOCUMENT.matcher(target.getPath()).find() || element.hasAttr("download")
                || element.attr("type").equalsIgnoreCase("application/pdf")) {
            localAttribute(element, "href", base, relations.contains("stylesheet") ? ".css" : "");
        } else {
            element.attr("href", target.toASCIIString());
        }
    }

    private void localAttribute(Element element, String name, URI base, String hint)
            throws IOException, InterruptedException {
        if (element.hasAttr(name)) {
            element.attr(name, capture.resource(base, element.attr(name), file, hint));
        }
    }

    String css(URI base, String text) throws IOException, InterruptedException {
        var matcher = CSS_URL.matcher(text);
        var result = new StringBuilder();
        while (matcher.find()) {
            if (matcher.group().startsWith("/*")) {
                continue;
            }
            boolean imported = matcher.group(4) != null;
            String value = imported ? matcher.group(4) : matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            String local = capture.resource(base, value, file, imported ? ".css" : "");
            if (!local.equals(value)) {
                String quoted = "\"" + local.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                String replacement = imported ? "@import " + quoted : "url(" + quoted + ")";
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
        }
        return matcher.appendTail(result).toString();
    }

    private String srcset(URI base, String text) throws IOException, InterruptedException {
        var result = new StringBuilder();
        int cursor = 0;
        while (cursor < text.length()) {
            char current = text.charAt(cursor);
            if (Character.isWhitespace(current) || current == ',') {
                result.append(current);
                cursor++;
                continue;
            }
            int start = cursor;
            while (cursor < text.length() && !Character.isWhitespace(text.charAt(cursor))) {
                cursor++;
            }
            String url = text.substring(start, cursor);
            int end = url.length();
            while (end > 0 && url.charAt(end - 1) == ',') {
                end--;
            }
            result.append(capture.resource(base, url.substring(0, end), file, "")).append(url.substring(end));
            if (end == url.length()) {
                int comma = text.indexOf(',', cursor);
                int next = comma < 0 ? text.length() : comma + 1;
                result.append(text, cursor, next);
                cursor = next;
            }
        }
        return result.toString();
    }
}
