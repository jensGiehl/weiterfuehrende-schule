package de.agiehl.telegram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public final class TelegramClient {

    private static final int POLL_SECONDS = 20;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final JsonMapper mapper;
    private final URI apiBase;
    private final String token;
    private final long chatId;
    private volatile Instant retryAfter = Instant.MIN;

    public TelegramClient(JsonMapper mapper,
                          @Value("${monitor.telegram.bot-token:}") String token,
                          @Value("${monitor.telegram.chat-id:}") String chatId,
                          @Value("${monitor.telegram.api-base:https://api.telegram.org}") URI apiBase) {
        this.mapper = mapper;
        this.apiBase = apiBase;
        this.token = token.strip();
        this.chatId = chatId.isBlank() ? 0 : Long.parseLong(chatId.strip());
        if (!this.token.isEmpty() && !this.token.matches("[0-9]+:[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Ungültiges Format für das Telegram-Bot-Token.");
        }
        if (!chatId.isBlank() && this.chatId >= 0) {
            throw new IllegalArgumentException("Die Telegram-Gruppen-ID muss negativ sein.");
        }
    }

    public void send(String message) throws IOException, InterruptedException {
        call("sendMessage", Map.of("chat_id", chatId, "text", message), REQUEST_TIMEOUT);
    }

    public boolean isConfigured() {
        return !token.isEmpty() && chatId < 0;
    }

    long chatId() {
        return chatId;
    }

    JsonNode updates(long offset) throws IOException, InterruptedException {
        var result = call("getUpdates", Map.of("offset", offset, "timeout", POLL_SECONDS,
                "allowed_updates", List.of("message")), Duration.ofSeconds(POLL_SECONDS).plus(REQUEST_TIMEOUT));
        if (!result.isArray()) {
            throw new IOException("Telegram hat keine Update-Liste zurückgegeben.");
        }
        return result;
    }

    boolean hasUsername(String username) throws IOException, InterruptedException {
        var bot = call("getMe", Map.of(), REQUEST_TIMEOUT);
        if (username.equalsIgnoreCase(bot.path("username").stringValue(""))) {
            return true;
        }
        for (var alias : bot.path("active_usernames")) {
            if (username.equalsIgnoreCase(alias.stringValue(""))) {
                return true;
            }
        }
        return false;
    }

    private JsonNode call(String method, Map<String, ?> parameters, Duration timeout)
            throws IOException, InterruptedException {
        if (!isConfigured()) {
            throw new IOException("Telegram-Bot-Token oder Gruppen-ID fehlt.");
        }
        if (Instant.now().isBefore(retryAfter)) {
            throw new IOException("Die Telegram-Wartefrist läuft noch.");
        }
        var request = HttpRequest.newBuilder(apiBase.resolve("/bot" + token + "/" + method))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(parameters)))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            var body = mapper.readTree(response.body());
            if (response.statusCode() == 429) {
                long seconds = body.path("parameters").path("retry_after").longValue(5);
                retryAfter = Instant.now().plusSeconds(Math.clamp(seconds, 1, Integer.MAX_VALUE));
            }
            if (response.statusCode() != 200 || !body.path("ok").booleanValue(false) || !body.hasNonNull("result")) {
                throw new IOException("Telegram hat " + method + " abgelehnt (HTTP " + response.statusCode() + ").");
            }
            return body.get("result");
        } catch (JacksonException ex) {
            throw new IOException("Telegram lieferte eine ungültige Antwort auf " + method + ".");
        }
    }
}
