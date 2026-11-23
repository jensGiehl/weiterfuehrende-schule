package de.agiehl.monitor;

import de.agiehl.monitor.MonitorState.Kind;
import de.agiehl.monitor.MonitorState.Message;
import de.agiehl.telegram.TelegramClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class OutboxSender {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxSender.class);

    private final Clock clock;
    private final StateStore store;
    private final TelegramClient telegram;
    private Instant nextAttempt = Instant.MIN;

    OutboxSender(Clock clock, StateStore store, TelegramClient telegram) {
        this.clock = clock;
        this.store = store;
        this.telegram = telegram;
    }

    @Scheduled(fixedDelay = 1000)
    void sendPending() {
        Instant now = clock.instant();
        if (!telegram.isConfigured() || now.isBefore(nextAttempt)) {
            return;
        }
        nextMessage(store.snapshot().outbox(), now).ifPresent(this::deliver);
    }

    static Optional<Message> nextMessage(List<Message> messages, Instant now) {
        return messages.stream()
                .filter(message -> message.kind() != Kind.OUTAGE || MonitorSchedule.canSendOutage(now))
                .filter(message -> message.kind() != Kind.RECOVERY || messages.stream().noneMatch(pending ->
                        pending.kind() == Kind.OUTAGE && Objects.equals(message.outageKey(), pending.outageKey())))
                .min(Comparator.comparingInt(message -> switch (message.kind()) {
                    case IMPORTANT -> 0;
                    case NEWS -> 1;
                    default -> 2;
                }));
    }

    private void deliver(Message message) {
        try {
            if (message.kind() == Kind.OUTAGE && !MonitorSchedule.canSendOutage(clock.instant())) {
                return;
            }
            telegram.send(message.text());
            store.update(state -> state.afterDelivery(message.id()));
        } catch (IOException failure) {
            nextAttempt = clock.instant().plusSeconds(15);
            LOG.warn("Telegram-Nachricht bleibt zur erneuten Zustellung vorgemerkt: {}", failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
