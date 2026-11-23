package de.agiehl.monitor;

import de.agiehl.archive.PageArchive;
import de.agiehl.monitor.MonitorState.Kind;
import de.agiehl.monitor.MonitorState.Message;
import de.agiehl.sourcenews.NewsItem;
import de.agiehl.sourcenews.NewsReader;
import de.agiehl.sourcenews.School;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorTest {

    @TempDir
    Path directory;

    @Test
    void switchesPollingCadenceAndOutageThresholdAtBerlinMidnight() {
        assertThat(MonitorSchedule.currentCheckSlot(berlin("2026-12-24T23:59:59")))
                .isEqualTo(berlin("2026-12-24T23:00:00"));
        assertThat(MonitorSchedule.currentCheckSlot(berlin("2026-12-25T00:29:59")))
                .isEqualTo(berlin("2026-12-25T00:15:00"));
        assertThat(MonitorSchedule.currentCheckSlot(berlin("2027-01-03T23:59:59")))
                .isEqualTo(berlin("2027-01-03T23:45:00"));
        assertThat(MonitorSchedule.currentCheckSlot(berlin("2027-01-04T00:02:59")))
                .isEqualTo(berlin("2027-01-04T00:02:00"));
        assertThat(MonitorSchedule.outageThreshold(berlin("2027-01-03T23:59:59"))).isEqualTo(Duration.ofHours(6));
        assertThat(MonitorSchedule.outageThreshold(berlin("2027-01-04T00:00:00"))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void announcesStartupAndReportsTheFirstCheckOnceWithCountsAndCurrentSettings() throws Exception {
        var now = berlin("2026-11-24T09:00:00");
        var latest = new NewsItem("Anmeldung geöffnet", URI.create("https://igs-frankenthal.de/anmeldung/"));
        var older = new NewsItem("Herbstkonzert", URI.create("https://igs-frankenthal.de/konzert/"));
        try (var store = state()) {
            for (School school : School.values()) {
                assertThat(store.snapshot().site(school).enabled()).isTrue();
            }
            var coordinator = new MonitorCoordinator(store);
            coordinator.handle(1, School.KG, false);
            var reader = mock(NewsReader.class);
            when(reader.read(School.IGS)).thenAnswer(call -> {
                assertThat(OutboxSender.nextMessage(store.snapshot().outbox(), now).orElseThrow().text())
                        .contains("Schulnews-Monitor gestartet");
                return List.of(latest, older);
            });
            when(reader.read(School.EDIGHEIM)).thenReturn(List.of());
            when(reader.read(School.AEG)).thenThrow(new IOException("HTTP 503"));
            var tasks = new MonitorTasks(Clock.fixed(now, MonitorSchedule.ZONE), store, coordinator, reader,
                    mock(PageArchive.class));
            try {
                tasks.tick();
                await().pollInSameThread().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    tasks.tick();
                    assertThat(store.snapshot().outbox()).filteredOn(message -> message.kind() == Kind.STARTUP)
                            .hasSize(2);
                });
                tasks.tick();
                var outbox = store.snapshot().outbox();
                assertThat(outbox).filteredOn(message -> message.kind() == Kind.STARTUP)
                        .satisfiesExactly(announcement -> {
                            assertThat(announcement.text()).contains("Ich suche jetzt", "poste ich es hier",
                                    "Sonntags um 20:00 Uhr");
                            assertThat(OutboxSender.nextMessage(outbox, now)).contains(announcement);
                        }, report -> assertThat(report.text())
                                .contains("2 von 3 aktiven Seiten erfolgreich geprüft", "2 Beiträge gelesen",
                                        "0 Beiträge gelesen", latest.title(), latest.url().toString(),
                                        "Aktiv · Abruf fehlgeschlagen", "KG · Karolinen-Gymnasium Frankenthal\nPausiert",
                                        "STOP IGS", "START IGS", "STATUS")
                                .doesNotContain(older.title()));
                assertThat(outbox).anyMatch(message -> message.kind() == Kind.IMPORTANT);
                for (School school : List.of(School.IGS, School.EDIGHEIM, School.AEG)) {
                    verify(reader).read(school);
                }
                verify(reader, never()).read(School.KG);
            } finally {
                tasks.stop();
            }
        }
    }

    @Test
    void queuesCurrentSettingsOnlyOnceForAStatusCommand() throws Exception {
        try (var store = state()) {
            var coordinator = new MonitorCoordinator(store);
            coordinator.handle(1, School.KG, false);
            coordinator.status(2);
            coordinator.status(2);

            assertThat(coordinator.updateOffset()).isEqualTo(2);
            assertThat(store.snapshot().outbox()).hasSize(2);
            assertThat(store.snapshot().outbox().getLast().text())
                    .contains("✅ Aktiv · IGS Edigheim", "⏸️ Pausiert · Karolinen-Gymnasium Frankenthal",
                            "STOP KG", "START KG");
        }
    }

    @Test
    void persistsArchivedRevisionsAndAvoidsDuplicateNotificationsAcrossRestarts() throws Exception {
        var baseline = new NewsItem("Adventskonzert", URI.create("https://igs-frankenthal.de/konzert/"));
        var admission = new NewsItem("ONLINE-ANMELDUNG geöffnet", URI.create("https://igs-frankenthal.de/anmeldung/"));
        var now = berlin("2027-01-04T08:00:00");
        try (var store = state()) {
            var coordinator = new MonitorCoordinator(store);
            coordinator.recordNews(School.IGS, 0, List.of(baseline, admission), now);
            assertThat(store.snapshot().archives()).hasSize(2);
            assertThat(store.snapshot().outbox()).singleElement().satisfies(message -> {
                assertThat(message.kind()).isEqualTo(Kind.IMPORTANT);
                assertThat(message.text()).contains("WICHTIG", admission.title(), admission.url().toString());
            });
            coordinator.handle(42, School.AEG, false);
        }
        try (var store = state()) {
            var coordinator = new MonitorCoordinator(store);
            assertThat(coordinator.updateOffset()).isEqualTo(42);
            assertThat(store.snapshot().site(School.AEG).enabled()).isFalse();
            coordinator.recordNews(School.IGS, 0, List.of(baseline, admission), now);
            assertThat(store.snapshot().outbox()).hasSize(2);
            var updated = new NewsItem("Konzerttermin geändert", baseline.url());
            coordinator.recordNews(School.IGS, 0, List.of(updated, admission), now);
            assertThat(store.snapshot().archives()).hasSize(3);
            assertThat(store.snapshot().outbox().getLast().text()).contains(updated.title(), updated.url().toString());
        }
    }

    @Test
    void importsLegacyStateOnlyWhenTheDatabaseDoesNotContainState() throws Exception {
        var seen = new MonitorState.Site(false, 1, true, Set.of("known-revision"), null, false);
        var job = new MonitorState.ArchiveJob(School.IGS, URI.create("https://igs-frankenthal.de/anmeldung/"),
                true, 0, berlin("2027-01-04T08:00:00"));
        var message = new Message(Kind.IMPORTANT, "Anmeldung geöffnet");
        var legacy = MonitorState.initial().withSite(School.IGS, seen, Map.of("known-revision", job), List.of(message))
                .withOffset(42);
        var legacyFile = directory.resolve("state.json");
        byte[] original = JsonMapper.builder().build().writeValueAsBytes(legacy);
        Files.write(legacyFile, original);

        try (var store = state()) {
            assertThat(store.snapshot()).isEqualTo(legacy);
            store.update(state -> state.withOffset(43));
        }
        try (var store = state()) {
            assertThat(store.snapshot()).isEqualTo(legacy.withOffset(43));
        }
        assertThat(directory.resolve("monitor.mv.db")).isRegularFile();
        assertThat(Files.readAllBytes(legacyFile)).isEqualTo(original);
    }

    @Test
    void preventsTwoMonitorsFromUsingTheSameDataDirectory() throws Exception {
        try (var store = state()) {
            assertThatIOException().isThrownBy(() -> state().close()).withMessageContaining("already in use");
            store.update(state -> state.withOffset(42));
        }
        try (var store = state()) {
            assertThat(store.snapshot().updateOffset()).isEqualTo(42);
        }
    }

    @Test
    void ignoresStaleChecksAfterStopAndStart() throws Exception {
        try (var store = state()) {
            var coordinator = new MonitorCoordinator(store);
            var now = berlin("2027-01-04T08:00:00");
            var item = new NewsItem("Anmeldung", URI.create("https://igs-frankenthal.de/anmeldung/"));
            coordinator.handle(5, School.IGS, false);
            coordinator.recordNews(School.IGS, 0, List.of(item), now);
            coordinator.handle(6, School.IGS, true);
            coordinator.recordNews(School.IGS, 0, List.of(item), now);
            assertThat(store.snapshot().archives()).isEmpty();
            assertThat(store.snapshot().site(School.AEG).enabled()).isTrue();
            coordinator.recordNews(School.IGS, 2, List.of(item), now);
            assertThat(store.snapshot().archives()).hasSize(1);
        }
    }

    @Test
    void reportsOnlySustainedOutagesAndStillAllowsNewsAtNight() throws Exception {
        try (var store = state()) {
            var coordinator = new MonitorCoordinator(store);
            coordinator.recordFailure(School.AEG, 0, berlin("2027-01-04T21:45:00"));
            coordinator.recordFailure(School.AEG, 0, berlin("2027-01-04T21:59:59"));
            coordinator.recordFailure(School.AEG, 0, berlin("2027-01-04T22:15:00"));
            assertThat(store.snapshot().outbox()).isEmpty();
            coordinator.recordFailure(School.AEG, 0, berlin("2027-01-05T06:00:00"));
            coordinator.recordFailure(School.AEG, 0, berlin("2027-01-05T06:01:00"));
            coordinator.recordNews(School.AEG, 0, List.of(), berlin("2027-01-05T22:01:00"));
            List<Message> messages = store.snapshot().outbox();
            assertThat(messages).extracting(Message::kind).containsExactly(Kind.OUTAGE, Kind.RECOVERY);
            Instant night = berlin("2027-01-05T22:01:00");
            assertThat(OutboxSender.nextMessage(messages, night)).isEmpty();
            assertThat(OutboxSender.nextMessage(List.of(messages.getLast()), night)).contains(messages.getLast());
            Message news = new Message(Kind.IMPORTANT, "Anmeldung");
            assertThat(OutboxSender.nextMessage(List.of(messages.getFirst(), messages.getLast(), news), night))
                    .contains(news);
        }
    }

    @Test
    void queuesJanuaryReminderAndSundayUpdateOnceWithAllCommands() throws Exception {
        try (var store = state()) {
            var coordinator = new MonitorCoordinator(store);
            coordinator.enqueueScheduledMessages(berlin("2027-01-03T19:59:59"));
            coordinator.enqueueScheduledMessages(berlin("2027-01-04T07:59:59"));
            assertThat(store.snapshot().outbox()).isEmpty();
            coordinator.enqueueScheduledMessages(berlin("2027-01-03T20:00:00"));
            coordinator.enqueueScheduledMessages(berlin("2027-01-03T20:00:01"));
            coordinator.enqueueScheduledMessages(berlin("2027-01-04T08:00:00"));
            coordinator.enqueueScheduledMessages(berlin("2027-01-04T09:00:00"));
            assertThat(store.snapshot().outbox()).hasSize(2);
            for (School school : School.values()) {
                assertThat(store.snapshot().outbox().getFirst().text())
                        .contains("STOP " + school.name(), "START " + school.name());
            }
            assertThat(store.snapshot().outbox().getLast().text()).contains("Anmeldung", "freigeschaltet");
        }
    }

    private StateStore state() throws Exception {
        return new StateStore(directory.toString());
    }

    private static Instant berlin(String local) {
        return LocalDateTime.parse(local).atZone(MonitorSchedule.ZONE).toInstant();
    }
}
