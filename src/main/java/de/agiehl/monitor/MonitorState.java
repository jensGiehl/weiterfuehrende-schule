package de.agiehl.monitor;

import de.agiehl.sourcenews.School;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

record MonitorState(Map<School, Site> sites, Map<String, ArchiveJob> archives, List<Message> outbox,
                    long updateOffset, boolean reminderQueued, LocalDate lastWeeklyUpdate) {

    MonitorState {
        sites = Map.copyOf(sites);
        archives = Collections.unmodifiableMap(new LinkedHashMap<>(archives));
        outbox = List.copyOf(outbox);
    }

    static MonitorState initial() {
        return new MonitorState(Map.of(), Map.of(), List.of(), 0, false, null);
    }

    Site site(School school) {
        return sites.getOrDefault(school, Site.INITIAL);
    }

    MonitorState withSite(School school, Site site, Map<String, ArchiveJob> jobs, List<Message> messages) {
        var changedSites = new EnumMap<School, Site>(School.class);
        changedSites.putAll(sites);
        changedSites.put(school, site);
        var changedJobs = new LinkedHashMap<>(archives);
        changedJobs.putAll(jobs);
        return new MonitorState(changedSites, changedJobs, withMessages(messages), updateOffset, reminderQueued,
                lastWeeklyUpdate);
    }

    MonitorState withOffset(long offset) {
        return new MonitorState(sites, archives, outbox, offset, reminderQueued, lastWeeklyUpdate);
    }

    MonitorState withScheduledMessages(List<Message> messages, boolean reminder, LocalDate weekly) {
        return new MonitorState(sites, archives, withMessages(messages), updateOffset, reminder, weekly);
    }

    MonitorState afterDelivery(UUID id) {
        return new MonitorState(sites, archives, outbox.stream().filter(message -> !message.id().equals(id)).toList(),
                updateOffset, reminderQueued, lastWeeklyUpdate);
    }

    MonitorState afterArchive(String revision, ArchiveJob retry) {
        var changedJobs = new LinkedHashMap<>(archives);
        if (retry == null) {
            changedJobs.remove(revision);
        } else {
            changedJobs.put(revision, retry);
        }
        return new MonitorState(sites, changedJobs, outbox, updateOffset, reminderQueued, lastWeeklyUpdate);
    }

    private List<Message> withMessages(List<Message> messages) {
        var changedOutbox = new ArrayList<>(outbox);
        changedOutbox.addAll(messages);
        return changedOutbox;
    }

    record Site(boolean enabled, long generation, boolean initialized, Set<String> seen, Instant firstFailure,
                boolean outageQueued) {

        private static final Site INITIAL = new Site(true, 0, false, Set.of(), null, false);

        Site {
            seen = Set.copyOf(seen);
        }

        Site switched(boolean active) {
            return enabled == active ? this : new Site(active, generation + 1, initialized, seen, null, false);
        }
    }

    record ArchiveJob(School school, URI url, boolean important, int attempts, Instant nextAttempt) {
    }

    record Message(UUID id, Kind kind, String text, String outageKey) {

        Message(Kind kind, String text) {
            this(UUID.randomUUID(), kind, text, null);
        }

        Message(Kind kind, String text, String outageKey) {
            this(UUID.randomUUID(), kind, text, outageKey);
        }
    }

    enum Kind {
        IMPORTANT, NEWS, NORMAL, OUTAGE, RECOVERY
    }
}
