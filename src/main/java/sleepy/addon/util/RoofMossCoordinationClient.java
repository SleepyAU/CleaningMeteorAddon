package sleepy.addon.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Small asynchronous client for the shared Roof Mosser chunk API. */
public final class RoofMossCoordinationClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .build();
    private final String baseUrl;
    private final String project;
    private final String dimension;
    private final String worker;
    private final String apiKey;

    public RoofMossCoordinationClient(String baseUrl, String project, String dimension, String worker, String apiKey) {
        this.baseUrl = trimTrailingSlashes(baseUrl);
        this.project = project == null ? "" : project.trim();
        this.dimension = dimension == null ? "" : dimension.trim();
        this.worker = worker == null ? "" : worker.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public CompletableFuture<Reply> claim(int chunkX, int chunkZ, int leaseSeconds) {
        JsonObject body = new JsonObject();
        body.addProperty("worker", worker);
        body.addProperty("leaseSeconds", Math.max(60, leaseSeconds));
        return send("POST", chunkUri(chunkX, chunkZ) + "/claim", body.toString());
    }

    public CompletableFuture<Reply> complete(int chunkX, int chunkZ) {
        JsonObject body = new JsonObject();
        body.addProperty("worker", worker);
        return send("PUT", chunkUri(chunkX, chunkZ) + "/complete", body.toString());
    }

    public CompletableFuture<Reply> release(int chunkX, int chunkZ) {
        JsonObject body = new JsonObject();
        body.addProperty("worker", worker);
        return send("DELETE", chunkUri(chunkX, chunkZ) + "/claim", body.toString());
    }

    private CompletableFuture<Reply> send(String method, String uri, String body) {
        try {
            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofString(body == null ? "" : body);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(method, publisher);
            if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);

            return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseReply(response.statusCode(), response.body()))
                .exceptionally(throwable -> new Reply(Status.Error, "", readableMessage(throwable), 0));
        } catch (Throwable throwable) {
            return CompletableFuture.completedFuture(new Reply(Status.Error, "", readableMessage(throwable), 0));
        }
    }

    private Reply parseReply(int statusCode, String body) {
        try {
            JsonObject json = JsonParser.parseString(body == null || body.isBlank() ? "{}" : body).getAsJsonObject();
            String rawStatus = json.has("status") ? json.get("status").getAsString() : "";
            String owner = json.has("owner") && !json.get("owner").isJsonNull() ? json.get("owner").getAsString() : "";
            String message = json.has("message") ? json.get("message").getAsString() : "";
            int leaseSeconds = json.has("leaseSeconds") && !json.get("leaseSeconds").isJsonNull()
                ? Math.max(0, json.get("leaseSeconds").getAsInt()) : 0;

            Status status = switch (rawStatus.toLowerCase()) {
                case "available" -> Status.Available;
                case "claimed" -> Status.Claimed;
                case "complete" -> Status.Complete;
                default -> Status.Error;
            };
            if (statusCode >= 500 || statusCode == 401 || statusCode == 403) status = Status.Error;
            if (status == Status.Error && message.isBlank()) message = "HTTP " + statusCode;
            return new Reply(status, owner, message, leaseSeconds);
        } catch (Throwable throwable) {
            return new Reply(Status.Error, "", "Invalid API response (HTTP " + statusCode + ")", 0);
        }
    }

    private String chunkUri(int chunkX, int chunkZ) {
        return baseUrl + "/v1/chunks/" + encode(project) + "/" + encode(dimension) + "/" + chunkX + "/" + chunkZ;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimTrailingSlashes(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    private static String readableMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    public enum Status {
        Available,
        Claimed,
        Complete,
        Error
    }

    public record Reply(Status status, String owner, String message, int leaseSeconds) {
        public boolean claimedBy(String worker) {
            return status == Status.Claimed && owner != null && owner.equals(worker);
        }
    }
}
