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
    void shouldCreateClientAndProvisionKeys() throws Exception {
        String payload = """
                {
                  "name": "acme",
                  "webhookUrl": "https://acme.test/webhooks",
                  "smsSoftQuota": 1000,
                  "smsHardQuota": 1500,
                  "whatsappSoftQuota": 800,
                  "whatsappHardQuota": 1200,
                  "rateLimitPerMinute": 120
                }
                """;

        mockMvc.perform(post("/clients")
                        .header("X-ADMIN-TOKEN", "test-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").exists())
                .andExpect(jsonPath("$.apiKey").value(org.hamcrest.Matchers.startsWith("cp_")))
                .andExpect(jsonPath("$.apiSecret").value(org.hamcrest.Matchers.startsWith("cs_")));
    }
}
