package dataguard.api;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** V1 默认只供本机演示：校验 Host/Origin，避免浏览器跨站调用本地导入接口。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalRequestFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final Set<String> allowedHosts;

    public LocalRequestFilter(ObjectMapper mapper,
                              @Value("${dataguard.allowed-hosts:localhost,127.0.0.1,[::1],::1}") String hosts) {
        this.mapper = mapper;
        allowedHosts = Arrays.stream(hosts.split(",")).map(String::strip)
                .map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        String policy = request.getRequestURI().endsWith("/report.html")
                ? "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"
                : "default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";
        response.setHeader("Content-Security-Policy", policy);
        if (request.getRequestURI().startsWith("/api/")) response.setHeader("Cache-Control", "no-store");
        if (!allowedHosts.contains(request.getServerName().toLowerCase(Locale.ROOT))) {
            reject(response, request, "HOST_NOT_ALLOWED", "此 Host 不在本机演示允许列表中。");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !sameOrigin(origin, request)) {
            reject(response, request, "ORIGIN_NOT_ALLOWED", "不允许跨站访问本机 DataGuard。");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean sameOrigin(String value, HttpServletRequest request) {
        try {
            URI origin = URI.create(value);
            int port = origin.getPort() == -1 ? ("https".equals(origin.getScheme()) ? 443 : 80) : origin.getPort();
            return origin.getHost() != null && origin.getUserInfo() == null
                    && origin.getRawQuery() == null && origin.getRawFragment() == null
                    && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                    && request.getScheme().equalsIgnoreCase(origin.getScheme())
                    && request.getServerName().equalsIgnoreCase(origin.getHost())
                    && request.getServerPort() == port;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void reject(HttpServletResponse response, HttpServletRequest request,
                          String code, String message) throws IOException {
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiError(Instant.now(), 403, code,
                message, request.getRequestURI(), Map.of()));
    }
}
