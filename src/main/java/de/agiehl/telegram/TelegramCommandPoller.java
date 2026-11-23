package de.agiehl.telegram;

import de.agiehl.sourcenews.School;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
final class TelegramCommandPoller {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramCommandPoller.class);
    private static final String COMMAND_PREFIX = "(?<slash>/?)";
    private static final String BOT_ADDRESS = "(?:@(?<recipient>[a-zA-Z0-9_]+))?";
    private static final Pattern SWITCH_COMMAND = Pattern.compile(
            COMMAND_PREFIX + "(?<action>START|STOP)" + BOT_ADDRESS + "\\h+(?<school>[a-zA-Z]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_COMMAND = Pattern.compile(
            COMMAND_PREFIX + "STATUS" + BOT_ADDRESS, Pattern.CASE_INSENSITIVE);

    private final TelegramClient telegram;
    private final TelegramCommandHandler handler;

    TelegramCommandPoller(TelegramClient telegram, TelegramCommandHandler handler) {
        this.telegram = telegram;
        this.handler = handler;
    }

    @Scheduled(fixedDelay = 5000)
    void poll() {
        if (!telegram.isConfigured()) {
            return;
        }
        try {
            receive();
        } catch (IOException ex) {
            LOG.warn("Telegram-Befehle konnten nicht verarbeitet werden: {}", ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    void receive() throws IOException, InterruptedException {
        long offset = handler.updateOffset();
        var updates = telegram.updates(offset);
        if (updates.isEmpty() && offset > 0) {
            handler.advance(0);
        }
        for (var update : updates) {
            long id = update.path("update_id").longValue(-1);
            if (id < 0 || id == Long.MAX_VALUE) {
                throw new IOException("Ein Telegram-Update enthält eine ungültige ID.");
            }
            if (id >= offset) {
                offset = id + 1;
                process(update.path("message"), offset);
            }
        }
    }

    private void process(JsonNode message, long nextOffset) throws IOException, InterruptedException {
        if (fromGroupMember(message)) {
            String text = message.path("text").stringValue("").strip();
            var status = STATUS_COMMAND.matcher(text);
            if (status.matches() && addressedToBot(status)) {
                handler.status(nextOffset);
                return;
            }
            var command = SWITCH_COMMAND.matcher(text);
            if (command.matches()) {
                School school;
                try {
                    school = School.valueOf(command.group("school").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    handler.advance(nextOffset);
                    return;
                }
                if (addressedToBot(command)) {
                    handler.handle(nextOffset, school, command.group("action").equalsIgnoreCase("START"));
                    return;
                }
            }
        }
        handler.advance(nextOffset);
    }

    private boolean addressedToBot(Matcher command) throws IOException, InterruptedException {
        String recipient = command.group("recipient");
        return recipient == null || (command.group("slash").equals("/") && telegram.hasUsername(recipient));
    }

    private boolean fromGroupMember(JsonNode message) {
        var chat = message.path("chat");
        String type = chat.path("type").stringValue("");
        if (chat.path("id").longValue(0) != telegram.chatId()
                || !(type.equals("group") || type.equals("supergroup"))
                || message.hasNonNull("forward_origin") || message.hasNonNull("via_bot")) {
            return false;
        }
        if (message.hasNonNull("sender_chat")) {
            return message.path("sender_chat").path("id").longValue(0) == telegram.chatId();
        }
        return !message.path("from").path("is_bot").booleanValue(true);
    }
}
