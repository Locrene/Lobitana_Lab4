package edu.cit.lobitana.supply;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Element;

import edu.cit.lobitana.common.AppCredentials;
import edu.cit.lobitana.common.AppInstance;
import edu.cit.lobitana.common.Retries;

/**
 * The Lab 3 adapter: the only class that knows LegacySupply speaks XML, needs a short-lived session
 * token and sometimes fails.
 *
 * It hides four things from the rest of the application: the XML, the session lifecycle (a rejected
 * session is renewed and the call replayed), backoff for 429/503, and idempotency via X-Request-Id so a
 * replayed purchase order is never placed twice. Every request also carries this process instance id.
 */
@Component
class LegacySupplyClient {

    private static final Logger log = LoggerFactory.getLogger(LegacySupplyClient.class);

    private final RestClient http;
    /** Same service, bounded patience: used for status polls, which the tracker repeats quickly. */
    private final RestClient quickHttp;
    private final AppCredentials credentials;
    private final AppInstance instance;

    private volatile String session;

    LegacySupplyClient(AppCredentials credentials,
                       AppInstance instance,
                       @Value("${legacysupply.base-url}") String baseUrl,
                       @Value("${legacysupply.connect-timeout-ms:5000}") int connectTimeout,
                       @Value("${legacysupply.read-timeout-ms:10000}") int readTimeout) {
        this.credentials = credentials;
        this.instance = instance;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeout));
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE)
                .build();
        JdkClientHttpRequestFactory quickFactory = new JdkClientHttpRequestFactory(httpClient);
        quickFactory.setReadTimeout(Duration.ofMillis(Math.min(readTimeout, 8_000)));
        this.quickHttp = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(quickFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE)
                .build();
        log.info("LegacySupply adapter pointed at {}", baseUrl);
    }

    CatalogResponse fetchCatalog() {
        return call("GET /catalog", () -> {
            String body = withSession(token -> http.get()
                    .uri("/catalog")
                    .headers(headers -> sessionHeaders(headers, token, null))
                    .exchange((request, response) -> readBody("GET /catalog", response), false));
            Element root = Xml.parse(body).getDocumentElement();
            List<SupplierCatalogEntry> items = new ArrayList<>();
            for (Element item : Xml.elements(root, "Item")) {
                String supplierSku = Xml.text(item, "SupplierSku");
                if (supplierSku == null || supplierSku.isBlank()) {
                    continue;
                }
                items.add(new SupplierCatalogEntry(
                        supplierSku,
                        Optional.ofNullable(Xml.text(item, "Description")).orElse(supplierSku),
                        Math.max(1, Xml.intText(item, "PackSize", 1)),
                        parseCost(Xml.text(item, "UnitCost"))));
            }
            return new CatalogResponse(items);
        });
    }

    /**
     * Place a purchase order. The request id is derived from our own buyer reference, so a retry after a
     * timeout is recognised by LegacySupply instead of becoming a second order.
     */
    PurchaseOrderAck placeOrder(String supplierSku, int qty, String buyerRef, String requestId) {
        String xml = "<PurchaseOrder>"
                + "<SupplierSku>" + Xml.escape(supplierSku) + "</SupplierSku>"
                + "<Qty>" + qty + "</Qty>"
                + "<BuyerRef>" + Xml.escape(buyerRef) + "</BuyerRef>"
                + "</PurchaseOrder>";
        return call("POST /purchase-orders " + supplierSku + " x" + qty, () -> {
            String body = withSession(token -> http.post()
                    .uri("/purchase-orders")
                    .contentType(MediaType.APPLICATION_XML)
                    .headers(headers -> sessionHeaders(headers, token, requestId))
                    .body(xml)
                    .exchange((request, response) -> readBody("POST /purchase-orders", response), false));
            Element root = Xml.parse(body).getDocumentElement();
            return new PurchaseOrderAck(
                    Xml.text(root, "PoNumber"),
                    Xml.intText(root, "StatusCode", 10),
                    Xml.text(root, "SupplierSku"),
                    Xml.intText(root, "Qty", qty),
                    Xml.text(root, "Uom"),
                    Xml.text(root, "BuyerRef"));
        });
    }

    PurchaseOrderStatusResponse orderStatus(String poNumber) {
        // The marketplace expects the new stock within 30 seconds of LegacySupply answering "delivered".
        // One attempt with a bounded wait: if no answer comes, the tracker asks again a few seconds
        // later, which leaves room for two lost answers inside those 30 seconds.
        return quickCall("GET /purchase-orders/" + poNumber, () -> {
            String body = withSession(token -> quickHttp.get()
                    .uri("/purchase-orders/{po}", poNumber)
                    .headers(headers -> sessionHeaders(headers, token, null))
                    .exchange((request, response) -> readBody("GET /purchase-orders/" + poNumber, response), false));
            Element root = Xml.parse(body).getDocumentElement();
            return new PurchaseOrderStatusResponse(
                    Optional.ofNullable(Xml.text(root, "PoNumber")).orElse(poNumber),
                    Xml.intText(root, "StatusCode", 10),
                    Xml.text(root, "SupplierSku"),
                    Xml.intText(root, "Qty", 0));
        });
    }

    /** The order placed under this reference, if LegacySupply has one. */
    Optional<PurchaseOrderAck> findByBuyerRef(String buyerRef) {
        return call("GET /purchase-orders?buyerRef=" + buyerRef, () -> {
            String body = withSession(token -> http.get()
                    .uri(uriBuilder -> uriBuilder.path("/purchase-orders").queryParam("buyerRef", buyerRef).build())
                    .headers(headers -> sessionHeaders(headers, token, null))
                    .exchange((request, response) -> readBody("GET /purchase-orders?buyerRef", response), false));
            Element root = Xml.parse(body).getDocumentElement();
            String poNumber = Xml.text(root, "PoNumber");
            if (poNumber == null || poNumber.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new PurchaseOrderAck(
                    poNumber,
                    Xml.intText(root, "StatusCode", 10),
                    Xml.text(root, "SupplierSku"),
                    Xml.intText(root, "Qty", 1),
                    Xml.text(root, "Uom"),
                    buyerRef));
        });
    }

    private <T> T quickCall(String operation, Supplier<T> body) {
        return withRetries(operation, 1, 300L, body);
    }

    /** Retry wrapper shared by every call. */
    private <T> T call(String operation, Supplier<T> body) {
        return withRetries(operation, 3, 600L, body);
    }

    private <T> T withRetries(String operation, int attempts, long firstPauseMs, Supplier<T> body) {
        return Retries.withBackoff(operation, attempts, firstPauseMs, () -> {
            try {
                return body.get();
            } catch (ResourceAccessException ex) {
                // No answer at all: retry, and the X-Request-Id keeps a repeated purchase order single.
                throw LegacySupplyException.transport(operation + " failed: " + ex.getMessage(), ex);
            }
        }, ex -> ex instanceof LegacySupplyException lse && lse.retryable());
    }

    /**
     * Run a call with a valid session, renewing it once if LegacySupply rejects it. Sessions are
     * short-lived by design, so this is a normal path, not an error path.
     */
    private <T> T withSession(Function<String, T> call) {
        String token = session;
        if (token == null) {
            token = authenticate();
        }
        try {
            return call.apply(token);
        } catch (LegacySupplyException ex) {
            if (!ex.sessionExpired()) {
                throw ex;
            }
            log.info("LegacySupply session rejected ({}), renewing", ex.code());
            session = null;
            return call.apply(authenticate());
        }
    }

    private synchronized String authenticate() {
        if (session != null) {
            return session;
        }
        String xml = "<AuthRequest>"
                + "<ClientId>" + Xml.escape(credentials.studentId()) + "</ClientId>"
                + "<ApiKey>" + Xml.escape(credentials.apiKey()) + "</ApiKey>"
                + "</AuthRequest>";
        String body = Retries.withBackoff("POST /auth/token", 4, 600L,
                () -> http.post()
                        .uri("/auth/token")
                        .contentType(MediaType.APPLICATION_XML)
                        .header("X-Client-Instance", instance.instanceIdHeader())
                        .body(xml)
                        .exchange((request, response) -> readBody("POST /auth/token", response), false),
                ex -> ex instanceof LegacySupplyException lse && lse.retryable() && !lse.sessionExpired());
        Element root = Xml.parse(body).getDocumentElement();
        String token = Xml.text(root, "SessionToken");
        if (token == null || token.isBlank()) {
            throw new LegacySupplyException(201, "E-AUTH-01", "no session token in response", false, false);
        }
        session = token;
        log.info("LegacySupply session opened");
        return token;
    }

    private void sessionHeaders(HttpHeaders headers, String token, String requestId) {
        headers.set("X-LS-Session", token);
        // Task 1: every supplier call carries the id of the process that made it.
        headers.set("X-Client-Instance", instance.instanceIdHeader());
        if (requestId != null && !requestId.isBlank()) {
            headers.set("X-Request-Id", requestId.length() > 80 ? requestId.substring(0, 80) : requestId);
        }
    }

    private String readBody(String operation, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        String body;
        HttpStatusCode status;
        try {
            status = response.getStatusCode();
            body = response.bodyTo(String.class);
        } catch (Exception ex) {
            throw LegacySupplyException.transport(operation + " failed: " + ex.getMessage(), ex);
        }
        if (status.is2xxSuccessful()) {
            return body == null ? "" : body;
        }
        throw toException(status.value(), body);
    }

    private LegacySupplyException toException(int status, String body) {
        String code = null;
        String message = Xml.abbreviate(body);
        try {
            if (body != null && body.contains("<LSError")) {
                Element root = Xml.parse(body).getDocumentElement();
                code = Xml.text(root, "Code");
                message = Optional.ofNullable(Xml.text(root, "Message")).orElse(message);
            }
        } catch (RuntimeException ignored) {
            // keep the raw body as the message
        }
        boolean sessionExpired = status == 401 && code != null && !"E-AUTH-01".equals(code);
        boolean retryable = sessionExpired || status == 429 || status >= 500;
        return new LegacySupplyException(status, code, message, retryable, sessionExpired);
    }

    private static BigDecimal parseCost(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }
}
