package de.agiehl.monitor;

import de.agiehl.sourcenews.NewsItem;

import java.util.List;

record SiteCheckResult(long generation, boolean successful, int count, NewsItem latest) {

    static SiteCheckResult read(long generation, List<NewsItem> items) {
        return new SiteCheckResult(generation, true, items.size(), items.isEmpty() ? null : items.getFirst());
    }

    static SiteCheckResult failed(long generation) {
        return new SiteCheckResult(generation, false, 0, null);
    }
}
