package com.cpass.platform.common.config;

import com.cpass.platform.common.security.AuditLogFilter;
import com.cpass.platform.common.security.ApiAuthenticationFilter;
import com.cpass.platform.common.security.ClientJwtAuthenticationFilter;
import com.cpass.platform.common.security.RequestCorrelation;
import com.cpass.platform.common.security.RequestCorrelationFilter;
import com.cpass.platform.common.security.SecurityMetricsService;
import com.cpass.platform.common.security.UserJwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:https://cpass-admin.vercel.app,http://localhost:4200}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   UserJwtAuthenticationFilter userJwtAuthenticationFilter,
                                                   ClientJwtAuthenticationFilter clientJwtAuthenticationFilter,
                                                   ApiAuthenticationFilter apiAuthenticationFilter,
                                                   RequestCorrelationFilter requestCorrelationFilter,
                                                   AuditLogFilter auditLogFilter,
                                                   SecurityMetricsService securityMetricsService) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/auth/token", "/auth/login", "/auth/2fa/verify", "/auth/forgot-password", "/auth/reset-password").permitAll()
                        .requestMatchers("/webhooks/delivery").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/clients").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/clients/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/clients/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/clients/*").hasAnyRole("ADMIN", "CLIENT")
                        .requestMatchers("/me/**").hasAnyRole("ADMIN", "CLIENT")
                        .anyRequest().permitAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, exception) -> {
                            securityMetricsService.recordAuthFailure("spring-security", "AUTH_REQUIRED", request.getServletPath());
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"code\":\"AUTH_REQUIRED\",\"message\":\"Authentication is required\",\"requestId\":\""
                                    + RequestCorrelation.requestId() + "\"}");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            securityMetricsService.recordAuthFailure("spring-security", "ACCESS_DENIED", request.getServletPath());
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"code\":\"ACCESS_DENIED\",\"message\":\"Access denied\",\"requestId\":\""
                                    + RequestCorrelation.requestId() + "\"}");
                        })
                )
                .addFilterBefore(requestCorrelationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(userJwtAuthenticationFilter, RequestCorrelationFilter.class)
                .addFilterAfter(clientJwtAuthenticationFilter, UserJwtAuthenticationFilter.class)
                .addFilterAfter(apiAuthenticationFilter, ClientJwtAuthenticationFilter.class)
                .addFilterAfter(auditLogFilter, ApiAuthenticationFilter.class)
                .cors(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "X-ADMIN-TOKEN", RequestCorrelation.REQUEST_ID_HEADER, RequestCorrelation.CORRELATION_ID_HEADER));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
