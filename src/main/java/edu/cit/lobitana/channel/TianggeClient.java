package edu.cit.lobitana.channel;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.lobitana.common.AppCredentials;
import edu.cit.lobitana.common.AppInstance;
import edu.cit.lobitana.common.Retries;

/**
 * The only class in this application that speaks HTTP to Tiangge (Rule 1 keeps it package-private).
 *
 * Every request carries the three identity headers, including X-Client-Instance with the UUID this process
 * minted at startup, which is how the marketplace knows the call came from the running application and not
 * from a person with Postman. Transient failures are retried with backoff; answers that cannot change are
 * reported as settled so callers stop repeating them.
 */
@Component
class TianggeClient {

    private static final Logger log = LoggerFactory.getLogger(TianggeClient.class);

    private final RestClient http;
    private final ObjectMapper json;
    private final AppInstance instance;

    TianggeClient(TianggeProperties properties, AppCredentials credentials, AppInstance instance, ObjectMapper json) {
        this.instance = instance;
        this.json = json;
        // The JDK client sends a proper Content-Length instead of a chunked body, and gives every call a
        // hard read timeout so a hanging marketplace cannot stall a scheduler thread.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        this.http = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader("X-Client-Id", credentials.studentId())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.apiKey())
                .defaultHeader("X-Client-Instance", instance.instanceIdHeader())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("Tiangge channel pointed at {} as instance {}", properties.baseUrl(), instance.instanceId());
    }

    HeartbeatResponse heartbeat() {
        HeartbeatRequest body = new HeartbeatRequest(
                "lab4-tiangge", instance.startedAt().toString(), instance.uptimeSeconds());
        return call("POST /instances/heartbeat", () -> http.post()
                .uri("/instances/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> read("POST /instances/heartbeat", response, HeartbeatResponse.class), false));
    }

    void publishListings(List<ListingPayload> listings) {
        call("PUT /listings", () -> http.put()
                .uri("/listings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(listings)
                .exchange((request, response) -> read("PUT /listings", response, String.class), false));
        log.info("published {} listing(s) to Tiangge", listings.size());
    }

    void publishStock(List<StockPayload> stock) {
        call("PUT /stock", () -> http.put()
                .uri("/stock")
                .contentType(MediaType.APPLICATION_JSON)
                .body(stock)
                .exchange((request, response) -> read("PUT /stock", response, String.class), false));
    }

    FeedPage fetchFeed(Long after, int limit) {
        String operation = "GET /feed?after=" + after;
        return call(operation, () -> http.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/feed").queryParam("limit", limit);
                    if (after != null && after > 0) {
                        uriBuilder.queryParam("after", after);
                    }
                    return uriBuilder.build();
                })
                .exchange((request, response) -> read(operation, response, FeedPage.class), false));
    }

    void sendDecision(String orderId, String decision, String shopOrderId, String reason) {
        DecisionRequest body = new DecisionRequest(decision, shopOrderId, trim(reason));
        call("POST /orders/" + orderId + "/decision " + decision, () -> http.post()
                .uri("/orders/{orderId}/decision", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> read("POST decision", response, String.class), false));
    }

    void sendResolution(String orderId, String status) {
        call("POST /orders/" + orderId + "/resolution " + status, () -> http.post()
                .uri("/orders/{orderId}/resolution", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ResolutionRequest(status))
                .exchange((request, response) -> read("POST resolution", response, String.class), false));
    }

    void confirmCancellation(String orderId) {
        call("POST /orders/" + orderId + "/cancellation", () -> http.post()
                .uri("/orders/{orderId}/cancellation", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CancellationRequest(true))
                .exchange((request, response) -> read("POST cancellation", response, String.class), false));
    }

    /**
     * Four attempts with exponential backoff. The marketplace is documented to answer 503 when it is busy,
     * and a decision has a 60 second deadline, so the first retries are quick.
     */
    private <T> T call(String operation, Supplier<T> body) {
        return Retries.withBackoff(operation, 4, 400L, () -> {
            try {
                return body.get();
            } catch (ResourceAccessException ex) {
                // No answer at all (timeout, connection refused): the same as a 503, try again.
                throw TianggeApiException.transport(operation, ex);
            }
        }, ex -> ex instanceof TianggeApiException tex && tex.retryable());
    }

    private <T> T read(String operation,
                       RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
                       Class<T> type) {
        HttpStatusCode status;
        String raw;
        try {
            status = response.getStatusCode();
            raw = response.bodyTo(String.class);
        } catch (Exception ex) {
            throw TianggeApiException.transport(operation, ex);
        }
        if (status.is2xxSuccessful()) {
            if (type == String.class) {
                return type.cast(raw == null ? "" : raw);
            }
            try {
                return json.readValue(raw, type);
            } catch (Exception ex) {
                throw new TianggeApiException(status.value(), "unreadable_body",
                        operation + " returned something we cannot read: " + ex.getMessage(), false, false);
            }
        }
        throw toException(operation, status.value(), raw);
    }

    private TianggeApiException toException(String operation, int status, String raw) {
        String code = null;
        String message = raw == null ? "" : raw;
        try {
            if (raw != null && raw.trim().startsWith("{")) {
                JsonNode parsed = json.readTree(raw);
                code = parsed.path("error").asText(null);
                message = parsed.path("message").asText(message);
            }
        } catch (Exception ignored) {
            // keep the raw body
        }
        boolean retryable = status == 503 || status >= 500 || status == 429;
        // 409 and 404 are documented as final; a request Tiangge calls invalid stays invalid too.
        boolean settled = status == 409 || status == 404 || status == 400 || status == 422;
        if (settled) {
            log.warn("{} will not change by repeating it: {} {}", operation, code, message);
        }
        return new TianggeApiException(status, code, message, retryable, settled);
    }

    private static String trim(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 200 ? reason : reason.substring(0, 200);
    }
}
