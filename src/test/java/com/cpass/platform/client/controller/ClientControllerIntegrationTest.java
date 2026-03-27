package com.cpass.platform.client.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCreateClientAndProvisionCredentials() throws Exception {
        String payload = """
                {
                  "name": "acme",
                  "email": "ops@acme.test",
                  "webhookUrl": "https://acme.test/webhooks",
                  "smsSoftQuota": 1000,
                  "smsHardQuota": 1500,
                  "whatsappSoftQuota": 800,
                  "whatsappHardQuota": 1200,
                  "rateLimitPerMinute": 120,
                  "scopes": ["sms:send", "otp:send", "kyc:read", "kyc:write"],
                  "kycLevelRequired": "LEVEL_1"
                }
                """;

        mockMvc.perform(post("/clients")
                        .header("X-ADMIN-TOKEN", "test-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.clientId").value(org.hamcrest.Matchers.startsWith("cl_")))
                .andExpect(jsonPath("$.clientSecret").value(org.hamcrest.Matchers.startsWith("cs_")));
    }

    @Test
    void shouldIssueJwtForValidClientCredentials() throws Exception {
        String clientPayload = """
                {
                  "name": "finbank",
                  "email": "ops@finbank.test",
                  "webhookUrl": "https://finbank.test/webhooks",
                  "smsSoftQuota": 1000,
                  "smsHardQuota": 1500,
                  "whatsappSoftQuota": 800,
                  "whatsappHardQuota": 1200,
                  "rateLimitPerMinute": 120
                }
                """;

        String provisioned = mockMvc.perform(post("/clients")
                        .header("X-ADMIN-TOKEN", "test-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientPayload))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String clientId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(provisioned).path("clientId").asText();
        String clientSecret = new com.fasterxml.jackson.databind.ObjectMapper().readTree(provisioned).path("clientSecret").asText();

        String tokenPayload = """
                {
                  "client_id": "%s",
                  "client_secret": "%s"
                }
                """.formatted(clientId, clientSecret);

        mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.expires_in").value(1800));
    }
}
