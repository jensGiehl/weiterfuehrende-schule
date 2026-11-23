package de.agiehl.monitor;

import de.agiehl.monitor.MonitorState.ArchiveJob;
import de.agiehl.monitor.MonitorState.Kind;
import de.agiehl.monitor.MonitorState.Message;
import de.agiehl.monitor.MonitorState.Site;
import de.agiehl.sourcenews.NewsItem;
import de.agiehl.sourcenews.School;
import de.agiehl.telegram.TelegramCommandHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
final class MonitorCoordinator implements TelegramCommandHandler {

    private static final DateTimeFormatter ERROR_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy 'um' HH:mm 'Uhr'",
            Locale.GERMANY).withZone(MonitorSchedule.ZONE);

    private final StateStore store;

    MonitorCoordinator(StateStore store) {
        this.store = store;
    }

    void enqueueStartupAnnouncement() throws IOException {
        store.update(state -> state.withMessage(new Message(Kind.STARTUP, """
                🔎 Schulnews-Monitor gestartet

                Ich suche jetzt auf den aktiven Schulwebseiten. Sobald ich etwas Neues finde, poste ich es hier.

                📅 Sonntags um 20:00 Uhr (deutsche Zeit) melde ich mich mit einem Status zur laufenden Suche.
                """.strip())));
    }

    void enqueueStartupReport(Map<School, SiteCheckResult> results) throws IOException {
        store.update(state -> state.withMessage(new Message(Kind.STARTUP, startupReport(state, results))));
    }

    void recordNews(School school, long generation, List<NewsItem> items, Instant now) throws IOException {
        store.update(state -> {
            Site site = state.site(school);
            if (!matches(site, generation)) {
                return state;
            }
            var seen = new HashSet<>(site.seen());
            var jobs = new LinkedHashMap<String, ArchiveJob>();
            var messages = new ArrayList<Message>();
            if (site.outageQueued()) {
                messages.add(new Message(Kind.RECOVERY, "✅ %s ist wieder erreichbar.\n%s".formatted(
                        school.displayName(), school.listingUri()), outageKey(school, site.firstFailure())));
            }
            for (NewsItem item : items) {
                String title = item.title();
                String revision = revisionId(school, item);
                if (seen.add(revision)) {
                    boolean important = title.toLowerCase(Locale.ROOT).contains("anmeldung");
                    jobs.put(revision, new ArchiveJob(school, item.url(), important, 0, now));
                    if (site.initialized() || important) {
                        messages.add(newsMessage(school, item, important));
                    }
                }
            }
            if (site.initialized() && site.firstFailure() == null && jobs.isEmpty()) {
                return state;
            }
            Site healthy = new Site(true, generation, true, seen, null, false);
            return state.withSite(school, healthy, jobs, messages);
        });
    }

    void recordFailure(School school, long generation, Instant now) throws IOException {
        store.update(state -> {
            Site site = state.site(school);
            if (!matches(site, generation)) {
                return state;
            }
            Instant since = site.firstFailure() == null ? now : site.firstFailure();
            boolean alert = !site.outageQueued() && MonitorSchedule.canSendOutage(now)
                    && !now.isBefore(since.plus(MonitorSchedule.outageThreshold(now)));
            if (!alert && site.firstFailure() != null) {
                return state;
            }
            List<Message> messages = alert ? List.of(new Message(Kind.OUTAGE,
                    "⚠️ %s lässt sich seit dem %s nicht zuverlässig abrufen.\n%s".formatted(
                            school.displayName(), ERROR_TIME.format(since), school.listingUri()),
                    outageKey(school, since))) : List.of();
            Site failing = new Site(true, generation, site.initialized(), site.seen(), since, site.outageQueued() || alert);
            return state.withSite(school, failing, Map.of(), messages);
        });
    }

    void enqueueScheduledMessages(Instant now) throws IOException {
        ZonedDateTime time = now.atZone(MonitorSchedule.ZONE);
        boolean reminderDue = time.toLocalDate().equals(MonitorSchedule.REMINDER_DATE)
                && !time.toLocalTime().isBefore(MonitorSchedule.REMINDER_TIME);
        boolean weeklyDue = time.getDayOfWeek() == DayOfWeek.SUNDAY
                && !time.toLocalTime().isBefore(MonitorSchedule.WEEKLY_UPDATE_TIME);
        if (reminderDue || weeklyDue) {
            store.update(state -> {
                boolean reminder = reminderDue && !state.reminderQueued();
                boolean weekly = weeklyDue && !time.toLocalDate().equals(state.lastWeeklyUpdate());
                if (!reminder && !weekly) {
                    return state;
                }
                var messages = new ArrayList<Message>();
                if (reminder) {
                    messages.add(new Message(Kind.NORMAL, "📌 Bei den IGS könnte die Anmeldung demnächst freigeschaltet "
                            + "werden. Ich beobachte die aktiven Newsseiten weiter.\n"
                            + School.IGS.listingUri() + "\n" + School.EDIGHEIM.listingUri()));
                }
                if (weekly) {
                    messages.add(new Message(Kind.NORMAL, weeklyUpdate(state)));
                }
                return state.withScheduledMessages(messages, state.reminderQueued() || reminder,
                        weekly ? time.toLocalDate() : state.lastWeeklyUpdate());
            });
        }
    }

    @Override
    public long updateOffset() {
        return store.snapshot().updateOffset();
    }

    @Override
    public void handle(long nextOffset, School school, boolean enabled) throws IOException {
        store.update(state -> {
            if (nextOffset <= state.updateOffset()) {
                return state;
            }
            Site changed = state.site(school).switched(enabled);
            String reply = enabled ? "✅ %s wird beobachtet." : "⏸️ %s ist pausiert.";
            return state.withSite(school, changed, Map.of(), List.of(new Message(Kind.NORMAL,
                    reply.formatted(school.displayName())))).withOffset(nextOffset);
        });
    }

    @Override
    public void status(long nextOffset) throws IOException {
        store.update(state -> nextOffset <= state.updateOffset() ? state : state.withMessage(new Message(Kind.NORMAL,
                "📋 Aktueller Status der Schulwebseiten\n\n" + siteSettings(state))).withOffset(nextOffset));
    }

    @Override
    public void advance(long nextOffset) throws IOException {
        store.update(state -> nextOffset != state.updateOffset() && (nextOffset == 0 || nextOffset > state.updateOffset())
                ? state.withOffset(nextOffset) : state);
    }

    private static Message newsMessage(School school, NewsItem item, boolean important) {
        String title = item.title().strip();
        String prefix = important ? "❗ WICHTIG · Anmeldung\n" : "📰 ";
        String header = prefix + school.displayName() + "\n";
        String link = "\n" + item.url();
        int titleLimit = 4096 - header.length() - link.length();
        if (titleLimit < 2) {
            link = "\n" + school.listingUri();
            titleLimit = 4096 - header.length() - link.length();
        }
        return new Message(important ? Kind.IMPORTANT : Kind.NEWS, header + shorten(title, titleLimit) + link);
    }

    private static String weeklyUpdate(MonitorState state) {
        return "📋 Wochenupdate zum Schulnews-Monitor\n\nIch suche weiter auf den aktiven Schulwebseiten.\n\n"
                + siteSettings(state);
    }

    private static String siteSettings(MonitorState state) {
        String sites = Arrays.stream(School.values())
                .map(school -> "%s %s\nSTOP %s · START %s".formatted(state.site(school).enabled()
                                ? "✅ Aktiv ·" : "⏸️ Pausiert ·", school.displayName(), school.name(), school.name()))
                .collect(Collectors.joining("\n\n"));
        return sites + "\n\n" + siteCommands();
    }

    private static String siteCommands() {
        return """
                ⚙️ Seiten in dieser Gruppe steuern
                STOP mit dem Schulkürzel pausiert die Seite, START schaltet sie ein.
                Zum Beispiel STOP IGS oder START IGS.
                STATUS zeigt alle Einstellungen.
                """.strip();
    }

    private static String startupReport(MonitorState state, Map<School, SiteCheckResult> results) {
        int active = 0;
        int successful = 0;
        int count = 0;
        var sites = new ArrayList<String>();
        for (School school : School.values()) {
            Site site = state.site(school);
            SiteCheckResult result = results.get(school);
            if (site.enabled()) {
                active++;
                if (result != null && result.generation() == site.generation() && result.successful()) {
                    successful++;
                    count += result.count();
                }
            }
            sites.add(startupSite(school, site, result));
        }
        String summary = active == 0 ? "Alle Seiten sind pausiert."
                : "%d von %d aktiven Seiten erfolgreich geprüft · %s gelesen"
                        .formatted(successful, active, newsCount(count));
        return """
                📊 Ergebnis der Startprüfung
                %s

                %s

                %s
                """.strip().formatted(summary, String.join("\n\n", sites), siteCommands());
    }

    private static String startupSite(School school, Site site, SiteCheckResult result) {
        String name = "%s · %s".formatted(school.name(), school.displayName());
        if (!site.enabled()) {
            return "⏸️ " + name + "\nPausiert";
        }
        if (result == null || result.generation() != site.generation()) {
            return "🔎 " + name + "\nAktiv · seit dem Start eingeschaltet";
        }
        if (!result.successful()) {
            return "⚠️ " + name + "\nAktiv · Abruf fehlgeschlagen";
        }
        String summary = "✅ " + name + "\nAktiv · " + newsCount(result.count()) + " gelesen";
        NewsItem latest = result.latest();
        if (latest == null) {
            return summary;
        }
        String url = latest.url().toString();
        String link = url.length() <= 500 ? "\n" + url : "";
        return summary + "\n📰 Neuester Beitrag · " + shorten(latest.title(), 180) + link;
    }

    private static String newsCount(int count) {
        return count + (count == 1 ? " Beitrag" : " Beiträge");
    }

    private static String shorten(String title, int limit) {
        if (title.length() <= limit) {
            return title;
        }
        int end = limit - 1;
        if (Character.isHighSurrogate(title.charAt(end - 1))) {
            end--;
        }
        return title.substring(0, end) + "…";
    }

    private static boolean matches(Site site, long generation) {
        return site.enabled() && site.generation() == generation;
    }

    private static String outageKey(School school, Instant since) {
        return school.name() + ":" + since;
    }

    private static String revisionId(School school, NewsItem item) {
        String input = school + "\n" + item.url().normalize() + "\n" + item.title().strip().toLowerCase(Locale.ROOT);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 must be available", error);
        }
    }
}
