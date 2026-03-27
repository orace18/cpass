package com.cpass.platform.client.controller;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.dto.ClientTokenRequest;
import com.cpass.platform.client.dto.ClientTokenResponse;
import com.cpass.platform.client.service.ClientService;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.common.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class ClientAuthController {

    private final ClientService clientService;
    private final JwtService jwtService;

    public ClientAuthController(ClientService clientService, JwtService jwtService) {
        this.clientService = clientService;
        this.jwtService = jwtService;
    }

    @PostMapping("/token")
    public ClientTokenResponse issueToken(@Valid @RequestBody ClientTokenRequest request) {
        ClientEntity client = clientService.authenticate(request.clientId(), request.clientSecret());
        JwtService.TokenIssue tokenIssue = jwtService.issueToken(client);
        return new ClientTokenResponse(tokenIssue.token(), tokenIssue.expiresAt().getEpochSecond() - java.time.Instant.now().getEpochSecond());
    }

    @PostMapping("/refresh")
    public ClientTokenResponse refresh(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_HEADER_REQUIRED", "Bearer token is required");
        }
        JwtService.VerifiedToken verifiedToken = jwtService.verify(authorization.substring(7));
        ClientEntity client = clientService.getClient(verifiedToken.clientId());
        JwtService.TokenIssue tokenIssue = jwtService.issueToken(client);
        return new ClientTokenResponse(tokenIssue.token(), tokenIssue.expiresAt().getEpochSecond() - java.time.Instant.now().getEpochSecond());
    }
}
