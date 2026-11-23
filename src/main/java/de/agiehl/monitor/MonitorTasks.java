package de.agiehl.monitor;

import de.agiehl.archive.PageArchive;
import de.agiehl.monitor.MonitorState.ArchiveJob;
import de.agiehl.sourcenews.NewsItem;
import de.agiehl.sourcenews.NewsReader;
import de.agiehl.sourcenews.School;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class MonitorTasks {

    private static final Logger LOG = LoggerFactory.getLogger(MonitorTasks.class);
    private static final int ARCHIVE_PARALLELISM = 4;

    private final Clock clock;
    private final StateStore store;
    private final MonitorCoordinator coordinator;
    private final NewsReader reader;
    private final PageArchive archive;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<School, Check> lastChecks = new EnumMap<>(School.class);
    private final Set<School> checking = ConcurrentHashMap.newKeySet();
    private final Set<String> archiving = ConcurrentHashMap.newKeySet();

    MonitorTasks(Clock clock, StateStore store, MonitorCoordinator coordinator, NewsReader reader, PageArchive archive) {
        this.clock = clock;
        this.store = store;
        this.coordinator = coordinator;
        this.reader = reader;
        this.archive = archive;
    }

    @Scheduled(fixedDelay = 1000)
    void tick() {
        Instant now = clock.instant();
        try {
            coordinator.enqueueScheduledMessages(now);
        } catch (IOException failure) {
            LOG.error("Geplante Telegram-Meldungen konnten nicht gespeichert werden.", failure);
        }

        MonitorState state = store.snapshot();
        Instant slot = MonitorSchedule.currentCheckSlot(now);
        for (School school : School.values()) {
            var site = state.site(school);
            var check = new Check(slot, site.generation());
            if (site.enabled() && !check.equals(lastChecks.get(school)) && checking.add(school)) {
                lastChecks.put(school, check);
                workers.submit(() -> checkSchool(school, check));
            }
        }
        int free = ARCHIVE_PARALLELISM - archiving.size();
        if (free > 0 && !state.archives().isEmpty()) {
            state.archives().entrySet().stream()
                    .filter(entry -> !archiving.contains(entry.getKey()))
                    .filter(entry -> state.site(entry.getValue().school()).enabled())
                    .filter(entry -> !entry.getValue().nextAttempt().isAfter(now))
                    .sorted(Comparator.comparing(entry -> !entry.getValue().important()))
                    .limit(free)
                    .forEach(entry -> {
                        String revision = entry.getKey();
                        archiving.add(revision);
                        workers.submit(() -> archiveNews(revision, entry.getValue()));
                    });
        }
    }

    private void checkSchool(School school, Check check) {
        try {
            var site = store.snapshot().site(school);
            if (!site.enabled() || site.generation() != check.generation()) {
                return;
            }
            List<NewsItem> items;
            try {
                items = reader.read(school);
            } catch (IOException failure) {
                LOG.warn("Newsabruf für {} fehlgeschlagen: {}", school.displayName(), failure.getMessage());
                coordinator.recordFailure(school, check.generation(), clock.instant());
                return;
            }
            coordinator.recordNews(school, check.generation(), items, clock.instant());
        } catch (IOException failure) {
            LOG.error("Prüfergebnis für {} konnte nicht gespeichert werden.", school.displayName(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            checking.remove(school);
        }
    }

    private void archiveNews(String revision, ArchiveJob job) {
        try {
            if (store.snapshot().site(job.school()).enabled()) {
                ArchiveJob retry = capture(revision, job);
                store.update(state -> state.afterArchive(revision, retry));
            }
        } catch (IOException failure) {
            LOG.error("Archivstatus für {} konnte nicht gespeichert werden.", job.url(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            archiving.remove(revision);
        }
    }

    private ArchiveJob capture(String revision, ArchiveJob job) throws InterruptedException {
        try {
            archive.save(job.school().name(), job.url(), revision);
            return null;
        } catch (IOException failure) {
            LOG.warn("Archivierung für {} fehlgeschlagen: {}", job.url(), failure.getMessage());
            int attempts = Math.min(job.attempts() + 1, 7);
            long seconds = Math.min(15L << (attempts - 1), 900);
            return new ArchiveJob(job.school(), job.url(), job.important(), attempts, clock.instant().plusSeconds(seconds));
        }
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record Check(Instant slot, long generation) {
    }
}
