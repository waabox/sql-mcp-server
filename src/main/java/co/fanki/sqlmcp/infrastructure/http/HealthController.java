package co.fanki.sqlmcp.infrastructure.http;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple health check controller.
 *
 * <p>Provides a lightweight health endpoint for Kubernetes probes
 * and container orchestration platforms.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@RestController
public class HealthController {

    /**
     * Health check endpoint.
     *
     * <p>Returns a simple "ok" response indicating the server is running.
     * This endpoint is used by:</p>
     * <ul>
     *   <li>Kubernetes liveness and readiness probes</li>
     *   <li>Docker HEALTHCHECK instructions</li>
     *   <li>Load balancer health checks</li>
     * </ul>
     *
     * @return "ok" if the server is healthy
     */
    @GetMapping(value = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        return "ok";
    }
}
