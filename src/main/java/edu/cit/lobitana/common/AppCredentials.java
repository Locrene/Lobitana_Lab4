package edu.cit.lobitana.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Credentials shared by both remote systems, read from the environment (Rule 4).
 *
 * STUDENT_ID and API_KEY are supplied as environment variables; nothing secret is committed.
 */
@Component
public class AppCredentials {

    private static final Logger log = LoggerFactory.getLogger(AppCredentials.class);

    private final String studentId;
    private final String apiKey;
    private final String appName;

    AppCredentials(@Value("${app.student-id:}") String studentId,
                   @Value("${app.api-key:}") String apiKey,
                   @Value("${app.app-name:lab4-tiangge}") String appName) {
        this.studentId = studentId == null ? "" : studentId.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.appName = appName;
        if (this.studentId.isEmpty() || this.apiKey.isEmpty()) {
            log.error("STUDENT_ID and/or API_KEY are not set. Remote calls will fail with 401 until you export them.");
        } else {
            log.info("credentials loaded for client id {} (api key {}...)", this.studentId,
                    this.apiKey.substring(0, Math.min(6, this.apiKey.length())));
        }
    }

    public String studentId() {
        return studentId;
    }

    public String apiKey() {
        return apiKey;
    }

    public String appName() {
        return appName;
    }

    public boolean configured() {
        return !studentId.isEmpty() && !apiKey.isEmpty();
    }
}
