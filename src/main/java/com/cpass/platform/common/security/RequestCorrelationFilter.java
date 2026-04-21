package com.cpass.platform.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final String MDC_REQUEST_ID = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incomingRequestId = request.getHeader(RequestCorrelation.REQUEST_ID_HEADER);
        if (incomingRequestId == null || incomingRequestId.isBlank()) {
            incomingRequestId = request.getHeader(RequestCorrelation.CORRELATION_ID_HEADER);
        }
        String requestId = RequestCorrelation.setOrGenerate(incomingRequestId);
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(RequestCorrelation.REQUEST_ID_HEADER, requestId);
        response.setHeader(RequestCorrelation.CORRELATION_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            RequestCorrelation.clear();
        }
    }
}
