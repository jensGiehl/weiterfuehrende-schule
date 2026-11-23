package de.agiehl.sourcenews;

import java.net.URI;

public enum School {
    IGS("Robert-Schuman-IGS Frankenthal", "https://igs-frankenthal.de/",
            ".blog-area .single-post2 .blog-content2 h4 a[href]"),
    EDIGHEIM("IGS Edigheim", "https://igs-edigheim.de/news",
            ".news-list-view .article h2 a[href]"),
    AEG("Albert-Einstein-Gymnasium Frankenthal", "https://aeg-frankenthal.de/",
            ".rt-tpg-container .rt-holder .entry-title a[href]"),
    KG("Karolinen-Gymnasium Frankenthal", "https://karolinen-gymnasium.de/",
            ".fusion-blog-shortcode article .entry-title a[href]");

    private final String displayName;
    private final URI listingUri;
    private final String headlineSelector;

    School(String displayName, String listingUrl, String headlineSelector) {
        this.displayName = displayName;
        this.listingUri = URI.create(listingUrl);
        this.headlineSelector = headlineSelector;
    }

    public String displayName() {
        return displayName;
    }

    public URI listingUri() {
        return listingUri;
    }

    String headlineSelector() {
        return headlineSelector;
    }
}
