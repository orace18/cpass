package com.cpass.platform.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Internal CPaaS API", version = "v1"),
        security = @SecurityRequirement(name = "x-api-key")
)
@SecurityScheme(name = "x-api-key", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER, paramName = "X-API-KEY")
@SecurityScheme(name = "x-timestamp", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER, paramName = "X-TIMESTAMP")
@SecurityScheme(name = "x-nonce", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER, paramName = "X-NONCE")
@SecurityScheme(name = "x-signature", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER, paramName = "X-SIGNATURE")
public class OpenApiConfig {
}
