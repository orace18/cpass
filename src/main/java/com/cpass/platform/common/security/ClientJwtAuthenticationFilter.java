package com.cpass.platform.common.security;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.common.exception.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class ClientJwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final ClientRepository clientRepository;
    private final ClientRateLimiter clientRateLimiter;

    public ClientJwtAuthenticationFilter(JwtService jwtService,
                                         ClientRepository clientRepository,
                                         ClientRateLimiter clientRateLimiter) {
        this.jwtService = jwtService;
        this.clientRepository = clientRepository;
        this.clientRateLimiter = clientRateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        if (path == null || path.isBlank()) {
            return false;
        }

        return "/auth/login".equals(path)
                || "/auth/2fa/verify".equals(path)
                || "/auth/forgot-password".equals(path)
                || "/auth/reset-password".equals(path)
                || path.startsWith("/admin")
                || path.startsWith("/clients")
                || path.equals("/me") || path.startsWith("/me/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            JwtService.VerifiedToken verifiedToken = jwtService.verify(authorization.substring(7));
            ClientEntity client = clientRepository.findById(verifiedToken.clientId())
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "CLIENT_NOT_FOUND", "Client no longer exists"));

            assertClientEnabled(client);
            assertIpAllowed(client, request);
            assertRateLimit(client);

            ClientPrincipal principal = new ClientPrincipal(
                    client.getId(),
                    client.getName(),
                    client.getClientId(),
                    client.getScopes(),
                    client.getKycLevelRequired()
            );

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    verifiedToken.scopes().stream()
                            .map(scope -> new SimpleGrantedAuthority(scope.authority()))
                            .toList()
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            ClientContextHolder.set(principal);
            try {
                filterChain.doFilter(request, response);
            } finally {
                ClientContextHolder.clear();
                SecurityContextHolder.clearContext();
            }
        } catch (ApiException exception) {
            response.setStatus(exception.getStatus().value());
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"" + exception.getCode()
                    + "\",\"message\":\"" + exception.getMessage()
                    + "\",\"requestId\":\"" + RequestCorrelation.requestId() + "\"}");
        }
    }

    private void assertClientEnabled(ClientEntity client) {
        if (!client.isEnabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CLIENT_DISABLED", "Client is disabled");
        }
    }

    private void assertRateLimit(ClientEntity client) {
        if (!clientRateLimiter.isAllowed(client.getId(), client.getRateLimitPerMinute())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "Rate limit exceeded");
        }
    }

    private void assertIpAllowed(ClientEntity client, HttpServletRequest request) {
        String whitelist = client.getIpWhitelist();
        if (whitelist == null || whitelist.isBlank()) {
            return;
        }
        String candidateIp = firstIp(request);
        List<String> allowedIps = List.of(whitelist.split(","));
        boolean allowed = allowedIps.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .anyMatch(allowedIp -> allowedIp.equals(candidateIp));
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "IP_NOT_ALLOWED", "Caller IP is not allowlisted");
        }
    }

    private String firstIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
