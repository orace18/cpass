package com.cpass.platform.email.service;

import com.cpass.platform.common.exception.ApiException;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final EmailConfigurationService emailConfigurationService;
    private final EmailBootstrapProperties bootstrapProperties;
    private final Optional<JavaMailSender> springMailSender;
    private final String defaultFromEmail;

    public EmailService(EmailConfigurationService emailConfigurationService,
                        EmailBootstrapProperties bootstrapProperties,
                        Optional<JavaMailSender> springMailSender,
                        @Value("${app.email.default-from:}") String defaultFromEmail) {
        this.emailConfigurationService = emailConfigurationService;
        this.bootstrapProperties = bootstrapProperties;
        this.springMailSender = springMailSender;
        this.defaultFromEmail = defaultFromEmail;
    }

    public void sendEmail(String to, String subject, String body) {
        sendEmail(null, to, subject, body);
    }

    public void sendEmail(UUID clientId, String to, String subject, String body) {
        Optional<EmailConfigurationService.SmtpSettings> configured = emailConfigurationService.resolveForClient(clientId);
        try {
            if (configured.isPresent()) {
                sendWithSettings(configured.get(), to, subject, body);
            } else if (bootstrapProperties.isConfigured()) {
                sendWithSettings(new EmailConfigurationService.SmtpSettings(
                        bootstrapProperties.host(),
                        bootstrapProperties.port(),
                        blankToNull(bootstrapProperties.username()),
                        blankToNull(bootstrapProperties.password()),
                        bootstrapProperties.fromEmail(),
                        blankToNull(bootstrapProperties.fromName()),
                        "BOOTSTRAP",
                        null
                ), to, subject, body);
            } else if (springMailSender.isPresent() && !defaultFromEmail.isBlank()) {
                sendWithSpringMailSender(springMailSender.get(), to, subject, body, defaultFromEmail);
            } else {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_NOT_CONFIGURED", "No SMTP configuration is available");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception exception) {
            log.error("SMTP send failed to={} subject={} : {}", to, subject, exception.getMessage(), exception);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EMAIL_SEND_FAILED", "Unable to send email");
        }
    }

    public void sendOtp(UUID clientId, String email, String code) {
        sendEmail(clientId, email, "Your CPaaS verification code",
                "Your verification code is " + code + ". It expires in 5 minutes.");
    }

    public void sendKycNotification(UUID clientId, String email, String status) {
        sendEmail(clientId, email, "KYC status update",
                "Your KYC request status is now: " + status + ".");
    }

    public void sendPasswordSetupEmail(UUID clientId, String email, String link, String contextName) {
        sendEmail(clientId, email, "Set your CPaaS password",
                "Your " + contextName + " account is ready.\n\nSet your password using this secure link:\n" + link);
    }

    public void sendPasswordResetEmail(UUID clientId, String email, String link) {
        sendEmail(clientId, email, "Reset your CPaaS password",
                "Use this secure link to reset your password. It expires in 15 minutes.\n" + link);
    }

    private void sendWithSettings(EmailConfigurationService.SmtpSettings settings, String to, String subject, String body) throws Exception {
        JavaMailSenderImpl sender = buildSender(settings);
        var message = sender.createMimeMessage();
        var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(body, false);
        if (settings.fromName() != null && !settings.fromName().isBlank()) {
            helper.setFrom(new InternetAddress(settings.fromEmail(), settings.fromName(), StandardCharsets.UTF_8.name()));
        } else {
            helper.setFrom(settings.fromEmail());
        }
        sender.send(message);
    }

    private void sendWithSpringMailSender(JavaMailSender sender, String to, String subject, String body, String fromEmail) throws Exception {
        JavaMailSenderImpl impl = (JavaMailSenderImpl) sender;
        var message = impl.createMimeMessage();
        var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(body, false);
        helper.setFrom(fromEmail);
        impl.send(message);
    }

    private JavaMailSenderImpl buildSender(EmailConfigurationService.SmtpSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setUsername(settings.username());
        sender.setPassword(settings.password());

        boolean sslDirect = settings.port() == 465;

        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.auth", settings.username() != null && !settings.username().isBlank());
        properties.put("mail.smtp.ssl.enable", String.valueOf(sslDirect));
        properties.put("mail.smtp.starttls.enable", String.valueOf(!sslDirect));
        properties.put("mail.smtp.ssl.trust", settings.host());
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "5000");
        properties.put("mail.smtp.writetimeout", "5000");
        return sender;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
