package de.agiehl.telegram;

import com.sun.net.httpserver.HttpServer;
import de.agiehl.sourcenews.School;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RecordingHandler handler = new RecordingHandler();
    private final Map<String, JsonNode> requests = new ConcurrentHashMap<>();
    private final Map<String, Object> responses = new ConcurrentHashMap<>();
    private HttpServer server;
    private TelegramClient client;
    private TelegramCommandPoller poller;

    @BeforeEach
    void startBotApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot123:test/", exchange -> {
            try (exchange) {
                String method = exchange.getRequestURI().getPath().substring("/bot123:test/".length());
                requests.put(method, mapper.readTree(exchange.getRequestBody().readAllBytes()));
                Object result = responses.get(method);
                byte[] body = result == null
                        ? "{\"ok\":false}".getBytes(StandardCharsets.UTF_8)
                        : mapper.writeValueAsBytes(Map.of("ok", true, "result", result));
                exchange.sendResponseHeaders(result == null ? 503 : 200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = new TelegramClient(mapper, "123:test", "-100123", base);
        poller = new TelegramCommandPoller(client, handler);
    }

    @AfterEach
    void stopBotApi() {
        server.stop(0);
    }

    @Test
    void sendsUtf8TextToTheConfiguredGroupAndReportsApiErrors() throws Exception {
        String text = "Neu an der Schule – „Grüße“ 🎒";
        responses.put("sendMessage", Map.of("message_id", 2));
        client.send(text);
        assertEquals(-100123L, requests.get("sendMessage").get("chat_id").longValue());
        assertEquals(text, requests.get("sendMessage").get("text").stringValue());
        responses.remove("sendMessage");
        assertThrows(IOException.class, () -> client.send(text));
    }

    @Test
    void processesGroupCommandsAndConsumesUnrelatedUpdates() throws Exception {
        handler.offset = 40;
        responses.put("getMe", Map.of("username", "Schulbot"));
        responses.put("getUpdates", List.of(
                update(40, -100123, "supergroup", "stop igs"),
                update(41, -100123, "group", "/Start@SchulBot   EDIGHEIM"),
                update(42, -100123, "group", "/stop@fremderbot AEG"),
                update(43, -999, "group", "STOP AEG"),
                update(44, -100123, "private", "STOP AEG"),
                update(45, -100123, "group", "bitte STOP AEG"),
                update(46, -100123, "group", "STOP Schule"),
                update(47, -100123, "group", "/STOP AEG"),
                update(48, -100123, "group", "START KG"),
                Map.of("update_id", 49, "edited_message", Map.of("text", "STOP IGS")),
                Map.of("update_id", 50, "message", Map.of("text", "STOP IGS",
                        "from", Map.of("is_bot", true), "chat", Map.of("id", -100123, "type", "group")))));

        poller.receive();

        var query = requests.get("getUpdates");
        assertEquals(40, query.path("offset").longValue());
        assertEquals("message", query.path("allowed_updates").get(0).stringValue());
        assertEquals(List.of(new Command(41, School.IGS, false), new Command(42, School.EDIGHEIM, true),
                new Command(48, School.AEG, false), new Command(49, School.KG, true)), handler.commands);
        assertEquals(List.of(43L, 44L, 45L, 46L, 47L, 50L, 51L), handler.skipped);
        assertEquals(51, handler.offset);
    }

    @Test
    void acceptsStatusOnlyFromTheConfiguredGroupAndForThisBot() throws Exception {
        responses.put("getMe", Map.of("username", "Schulbot"));
        responses.put("getUpdates", List.of(
                update(1, -100123, "group", " STATUS "),
                update(2, -100123, "supergroup", "/status@SchulBot"),
                update(3, -100123, "group", "/status@fremderbot"),
                update(4, -999, "group", "/status"),
                update(5, -100123, "group", "/status IGS"),
                update(6, -100123, "group", "STATUS@SchulBot"),
                update(7, -100123, "group", "/status")));

        poller.receive();

        assertEquals(List.of(2L, 3L, 8L), handler.statusRequests);
        assertEquals(List.of(4L, 5L, 6L, 7L), handler.skipped);
        assertEquals(8, handler.offset);
    }

    @Test
    void stopsAtFirstUncommittedUpdate() throws Exception {
        responses.put("getUpdates", List.of(update(1, -100123, "group", "STOP AEG"),
                update(2, -100123, "group", "START KG")));
        handler.failOnSave = true;

        assertThrows(IOException.class, poller::receive);

        assertEquals(0, handler.offset);
        handler.failOnSave = false;
        poller.receive();
        assertEquals(List.of(new Command(2, School.AEG, false), new Command(3, School.KG, true)), handler.commands);
    }

    @Test
    void clearsAcknowledgedOffsetSoTelegramCanRestartNumberingAfterAQuietWeek() throws Exception {
        handler.offset = 77;
        responses.put("getUpdates", List.of());
        poller.receive();
        assertEquals(77, requests.get("getUpdates").path("offset").longValue());
        assertEquals(0, handler.offset);
    }

    private Map<String, Object> update(long id, long chatId, String type, String text) {
        return Map.of("update_id", id, "message", Map.of("text", text,
                "from", Map.of("is_bot", false), "chat", Map.of("id", chatId, "type", type)));
    }

    private record Command(long offset, School school, boolean enabled) { }

    private static final class RecordingHandler implements TelegramCommandHandler {
        private final List<Command> commands = new ArrayList<>();
        private final List<Long> statusRequests = new ArrayList<>();
        private final List<Long> skipped = new ArrayList<>();
        private long offset;
        private boolean failOnSave;

        @Override
        public long updateOffset() {
            return offset;
        }

        @Override
        public void handle(long nextOffset, School school, boolean enabled) throws IOException {
            if (failOnSave) {
                throw new IOException("Disk full");
            }
            commands.add(new Command(nextOffset, school, enabled));
            offset = nextOffset;
        }

        @Override
        public void status(long nextOffset) {
            statusRequests.add(nextOffset);
            offset = nextOffset;
        }

        @Override
        public void advance(long nextOffset) {
            skipped.add(nextOffset);
            offset = nextOffset;
        }
    }
}
