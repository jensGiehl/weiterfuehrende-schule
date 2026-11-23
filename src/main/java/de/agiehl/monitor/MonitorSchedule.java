package de.agiehl.monitor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

final class MonitorSchedule {

    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    static final LocalDate REMINDER_DATE = LocalDate.of(2027, 1, 4);
    static final LocalTime WEEKLY_UPDATE_TIME = LocalTime.of(20, 0);
    static final LocalTime REMINDER_TIME = LocalTime.of(8, 0);

    private static final LocalDate QUARTER_HOURLY = LocalDate.of(2026, 12, 25);
    private static final LocalTime QUIET_START = LocalTime.of(22, 0);
    private static final LocalTime QUIET_END = LocalTime.of(6, 0);

    private MonitorSchedule() {
    }

    static Instant currentCheckSlot(Instant now) {
        ZonedDateTime local = now.atZone(ZONE).truncatedTo(ChronoUnit.MINUTES);
        int minutes = checkIntervalMinutes(local.toLocalDate());
        return local.withMinute(local.getMinute() / minutes * minutes).toInstant();
    }

    static int checkIntervalMinutes(LocalDate date) {
        if (date.isBefore(QUARTER_HOURLY)) {
            return 60;
        }
        return date.isBefore(REMINDER_DATE) ? 15 : 1;
    }

    static Duration outageThreshold(Instant now) {
        return now.atZone(ZONE).toLocalDate().isBefore(REMINDER_DATE) ? Duration.ofHours(6) : Duration.ofMinutes(30);
    }

    static boolean canSendOutage(Instant now) {
        LocalTime time = now.atZone(ZONE).toLocalTime();
        return !time.isBefore(QUIET_END) && time.isBefore(QUIET_START);
    }
}
