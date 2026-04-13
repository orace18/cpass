package com.cpass.platform.common.security;

import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.user.domain.UserEntity;
import com.cpass.platform.user.infra.UserRepository;
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

@Component
public class UserJwtAuthenticationFilter extends OncePerRequestFilter {

    private final UserJwtService userJwtService;
    private final UserRepository userRepository;

    public UserJwtAuthenticationFilter(UserJwtService userJwtService,
                                       UserRepository userRepository) {
        this.userJwtService = userJwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        if (path == null || path.isBlank()) {
            return true;
        }
        return !(path.startsWith("/admin")
                || path.startsWith("/clients")
                || path.equals("/me") || path.startsWith("/me/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            UserJwtService.VerifiedToken verified = userJwtService.verify(token);
            UserEntity user = userRepository.findById(verified.userId())
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_TOKEN", "User no longer exists"));

            if (!user.isEnabled()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "USER_DISABLED", "User is disabled");
            }

            UserPrincipal principal = new UserPrincipal(user.getId(), user.getEmail(), user.getRole(), user.getClientId());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority(user.getRole().authority()))
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);
            UserContextHolder.set(principal);
            try {
                filterChain.doFilter(request, response);
            } finally {
                UserContextHolder.clear();
                SecurityContextHolder.clearContext();
            }
        } catch (ApiException exception) {
            response.setStatus(exception.getStatus().value());
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"" + exception.getCode() + "\",\"message\":\"" + exception.getMessage() + "\"}");
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        String adminToken = request.getHeader("X-ADMIN-TOKEN");
        if (adminToken != null && !adminToken.isBlank()) {
            return adminToken.trim();
        }
        String queryToken = request.getParameter("token");
        return queryToken == null || queryToken.isBlank() ? null : queryToken.trim();
    }
}
