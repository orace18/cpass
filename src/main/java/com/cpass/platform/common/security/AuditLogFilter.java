package com.cpass.platform.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AuditLogFilter extends OncePerRequestFilter {

    private final AuditLogRepository auditLogRepository;
    private final AdminSecurityService adminSecurityService;

    public AuditLogFilter(AuditLogRepository auditLogRepository, AdminSecurityService adminSecurityService) {
        this.auditLogRepository = auditLogRepository;
        this.adminSecurityService = adminSecurityService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (shouldSkip(request.getRequestURI())) {
                return;
            }
            AuditLogEntity auditLog = new AuditLogEntity();
            auditLog.setMethod(request.getMethod());
            auditLog.setPath(request.getRequestURI());
            auditLog.setResultStatus(response.getStatus());
            auditLog.setOutcome(response.getStatus() >= 400 ? "FAILED" : "SUCCESS");
            auditLog.setIpAddress(firstIp(request));

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof ClientPrincipal principal) {
                auditLog.setActorType("CLIENT");
                auditLog.setActorId(principal.clientPublicId());
                auditLog.setClientId(principal.clientId());
            } else if (adminSecurityService.isAdminToken(request.getHeader("X-ADMIN-TOKEN"))) {
                auditLog.setActorType("ADMIN");
                auditLog.setActorId("admin");
            } else {
                auditLog.setActorType("ANONYMOUS");
                auditLog.setActorId(null);
            }

            auditLogRepository.save(auditLog);
        }
    }

    private boolean shouldSkip(String path) {
        return path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }

    private String firstIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
