package com.cpass.platform.email.controller;

import com.cpass.platform.email.dto.EmailConfigRequest;
import com.cpass.platform.email.dto.EmailConfigResponse;
import com.cpass.platform.email.dto.TestEmailRequest;
import com.cpass.platform.email.service.EmailConfigurationService;
import com.cpass.platform.email.service.EmailService;
import com.cpass.platform.user.dto.BasicResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class EmailConfigController {

    private final EmailConfigurationService emailConfigurationService;
    private final EmailService emailService;

    public EmailConfigController(EmailConfigurationService emailConfigurationService,
                                 EmailService emailService) {
        this.emailConfigurationService = emailConfigurationService;
        this.emailService = emailService;
    }

    @GetMapping("/config/email")
    public EmailConfigResponse getGlobalEmailConfig() {
        return emailConfigurationService.getGlobalConfig();
    }

    @PostMapping("/config/email")
    public EmailConfigResponse saveGlobalEmailConfig(@Valid @RequestBody EmailConfigRequest request) {
        return emailConfigurationService.saveGlobalConfig(request);
    }

    @GetMapping("/config/email/clients/{clientId}")
    public EmailConfigResponse getClientEmailConfig(@PathVariable UUID clientId) {
        return emailConfigurationService.getClientConfig(clientId);
    }

    @PostMapping("/config/email/clients/{clientId}")
    public EmailConfigResponse saveClientEmailConfig(@PathVariable UUID clientId,
                                                     @Valid @RequestBody EmailConfigRequest request) {
        return emailConfigurationService.saveClientConfig(clientId, request);
    }

    @PostMapping("/email/test")
    public BasicResponse testEmail(@Valid @RequestBody TestEmailRequest request) {
        UUID clientId = request.clientId() == null || request.clientId().isBlank() ? null : UUID.fromString(request.clientId());
        emailService.sendEmail(clientId, request.to(), "CPaaS SMTP test", "This is a test email from your CPaaS configuration.");
        return new BasicResponse("OK", "Test email sent.");
    }
}
