package edu.cit.lobitana.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Channel settings. Package-private: nothing outside the module configures the marketplace. */
@Component
class TianggeProperties {

    private final String baseUrl;
    private final int feedPageSize;
    private final int maxPagesPerCycle;
    private final int heartbeatSeconds;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    TianggeProperties(@Value("${tiangge.base-url}") String baseUrl,
                      @Value("${tiangge.feed-page-size:50}") int feedPageSize,
                      @Value("${tiangge.max-pages-per-cycle:20}") int maxPagesPerCycle,
                      @Value("${tiangge.heartbeat-seconds:30}") int heartbeatSeconds,
                      @Value("${tiangge.connect-timeout-ms:5000}") int connectTimeoutMs,
                      @Value("${tiangge.read-timeout-ms:10000}") int readTimeoutMs) {
        this.baseUrl = baseUrl;
        this.feedPageSize = Math.min(50, Math.max(1, feedPageSize));
        this.maxPagesPerCycle = Math.max(1, maxPagesPerCycle);
        this.heartbeatSeconds = Math.max(5, heartbeatSeconds);
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    String baseUrl() {
        return baseUrl;
    }

    int feedPageSize() {
        return feedPageSize;
    }

    int maxPagesPerCycle() {
        return maxPagesPerCycle;
    }

    int heartbeatSeconds() {
        return heartbeatSeconds;
    }

    int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    int readTimeoutMs() {
        return readTimeoutMs;
    }
}
