package com.cpass.platform.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignerTest {

    @Test
    void shouldSignPayloadWithHmacSha256() {
        String signature = HmacSigner.hmacSha256Hex("secret", "POST\n/messages/sms\n1710000000\nabc123\npayload-hash");
        assertThat(signature).isEqualTo("47ba91e0eb3ae04282aae4b6d846c3f024a85f875d016f07267c80580f415453");
    }
}
