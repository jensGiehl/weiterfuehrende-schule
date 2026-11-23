package de.agiehl.sourcenews;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class NewsReaderTest {
    static Stream<Arguments> newsLists() {
        return Stream.of(
                arguments(School.IGS, """
                        <section class="blog-area"><div class="single-post2"><div class="blog-content2">
                        <h4>%s</h4></div></div></section>
                        """),
                arguments(School.EDIGHEIM, """
                        <div class="news-list-view"><div class="article"><div class="header">
                        <h2>%s</h2></div></div></div>
                        """),
                arguments(School.AEG, """
                        <div class="rt-tpg-container"><div class="rt-holder"><div class="rt-detail">
                        <h3 class="entry-title">%s</h3></div></div></div>
                        """),
                arguments(School.KG, """
                        <div class="fusion-blog-shortcode"><article class="fusion-post-grid">
                        <h2 class="entry-title">%s</h2></article></div>
                        """));
    }

    @ParameterizedTest
    @MethodSource("newsLists")
    void findsArticleTitlesAndDirectUrlsInEachSchoolLayout(School school, String template) throws IOException {
        String path = "/meldung?jahr=27&stufe=5";
        String link = """
                <a href="/meldung?jahr=27&amp;stufe=5"><span>Anmeldung für Klasse 5</span></a>
                """;
        var html = "<nav><h2><a href='/kontakt'>Kontakt</a></h2></nav>" + template.formatted(link)
                + template.formatted(link) + template.formatted(link.replace("Klasse 5", "die Oberstufe"))
                + template.formatted("<a href='https://example.org'>Extern</a>")
                + template.formatted("<a href='#'>Platzhalter</a>")
                + template.formatted("<a href='/leer'></a>");
        var document = Jsoup.parse(html, school.listingUri().toString());
        var url = school.listingUri().resolve(path);

        assertEquals(List.of(new NewsItem("Anmeldung für Klasse 5", url),
                new NewsItem("Anmeldung für die Oberstufe", url)), NewsReader.parse(school, document));
    }

    @Test
    void reportsAnUnrecognizedLayout() {
        var document = Jsoup.parse("<main><h2>Wartung</h2></main>", School.IGS.listingUri().toString());

        assertThrows(IOException.class, () -> NewsReader.parse(School.IGS, document));
    }

    @Test
    void acceptsAnExplicitlyEmptyTypo3NewsList() throws IOException {
        var document = Jsoup.parse("<div class='news'><div class='no-news-found'>Keine Nachrichten</div></div>",
                School.EDIGHEIM.listingUri().toString());

        assertEquals(List.of(), NewsReader.parse(School.EDIGHEIM, document));
    }
}
