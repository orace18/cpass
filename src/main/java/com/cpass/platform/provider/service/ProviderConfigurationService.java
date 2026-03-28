package com.cpass.platform.provider.service;

import com.cpass.platform.admin.dto.ProviderConfigRequest;
import com.cpass.platform.admin.dto.ProviderConfigResponse;
import com.cpass.platform.admin.dto.ProviderSummaryResponse;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.common.security.SecretCryptoService;
import com.cpass.platform.messaging.domain.MessageChannel;
import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.cpass.platform.provider.domain.ProviderType;
import com.cpass.platform.provider.infra.ProviderConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ProviderConfigurationService {

    private static final Set<String> MASKED_KEYS = Set.of("password", "apiKey", "accessToken", "token");

    private static final ProviderProfile ROUTE_MOBILE_SMS = new ProviderProfile(
            "ROUTE_MOBILE",
            MessageChannel.SMS,
            Set.of("username", "password"),
            Set.of("password"),
            Set.of("username", "password", "senderId")
    );

    private static final ProviderProfile AFRICA_TALKING_SMS = new ProviderProfile(
            "AFRICA_TALKING",
            MessageChannel.SMS,
            Set.of("username", "apiKey"),
            Set.of("apiKey"),
            Set.of("username", "apiKey")
    );

    private static final ProviderProfile WHATSAPP_META = new ProviderProfile(
            "WHATSAPP_META",
            MessageChannel.WHATSAPP,
            Set.of("phoneNumberId", "accessToken"),
            Set.of("accessToken"),
            Set.of("phoneNumberId", "accessToken")
    );

    private static final Map<String, ProviderProfile> PROFILES = Map.of(
            ROUTE_MOBILE_SMS.code(), ROUTE_MOBILE_SMS,
            AFRICA_TALKING_SMS.code(), AFRICA_TALKING_SMS,
            WHATSAPP_META.code(), WHATSAPP_META
    );

    private final ProviderConfigRepository providerConfigRepository;
    private final ObjectMapper objectMapper;
    private final SecretCryptoService cryptoService;

    public ProviderConfigurationService(ProviderConfigRepository providerConfigRepository,
                                        ObjectMapper objectMapper,
                                        SecretCryptoService cryptoService) {
        this.providerConfigRepository = providerConfigRepository;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
    }

    public List<ProviderSummaryResponse> listProviders() {
        Sort.Order channelOrder = Sort.Order.asc("channel").nullsLast();
        return providerConfigRepository.findAll(Sort.by(
                        Sort.Order.asc("providerType"),
                        channelOrder,
                        Sort.Order.asc("priority"),
                        Sort.Order.asc("code")))
                .stream()
                .map(this::toSummary)
                .toList();
    }

    public ProviderSummaryResponse updateProvider(UUID id, Map<String, Object> patch) {
        ProviderConfigEntity entity = providerConfigRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND", "Provider not found: " + id));

        if (patch.containsKey("active")) {
            entity.setActive(Boolean.TRUE.equals(patch.get("active")));
        }
        if (patch.containsKey("priority") && patch.get("priority") instanceof Number number) {
            entity.setPriority(Math.max(1, number.intValue()));
        }

        return toSummary(providerConfigRepository.save(entity));
    }

    public ProviderConfigResponse getProviderConfig(String providerCode) {
        ProviderProfile profile = profileFor(providerCode);
        return toConfigResponse(resolveOrCreate(profile), profile);
    }

    public ProviderConfigResponse saveProviderConfig(ProviderConfigRequest request) {
        ProviderProfile profile = profileFor(request.provider());
        ProviderConfigEntity entity = resolveOrCreate(profile);
        Map<String, Object> mergedConfig = new LinkedHashMap<>(readRuntimeConfig(entity));

        mergeConfig(profile, mergedConfig, request.config());
        validateRequiredFields(profile, mergedConfig);

        if (request.enabled() != null) {
            entity.setActive(request.enabled());
        }
        if (request.priority() != null) {
            entity.setPriority(Math.max(1, request.priority()));
        }

        entity.setConfigJson(writeStoredConfig(profile, mergedConfig));
        return toConfigResponse(providerConfigRepository.save(entity), profile);
    }

    public String normalizeProviderCode(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROVIDER_REQUIRED", "Provider is required");
        }
        String normalized = providerCode.trim().toUpperCase(Locale.ROOT);
        if ("AFRICAS_TALKING".equals(normalized)) {
            return "AFRICA_TALKING";
        }
        return normalized;
    }

    public Map<String, Object> readRuntimeConfig(ProviderConfigEntity entity) {
        Map<String, Object> config = parseJson(entity.getConfigJson());

        decryptValue(config, "password");
        decryptValue(config, "apiKey");
        decryptValue(config, "token");
        decryptValue(config, "accessToken");

        if (matches(entity, "WHATSAPP_META", MessageChannel.WHATSAPP)
                && !hasText(config.get("accessToken"))
                && hasText(config.get("token"))) {
            config.put("accessToken", String.valueOf(config.get("token")));
        }

        return config;
    }

    private ProviderSummaryResponse toSummary(ProviderConfigEntity entity) {
        Map<String, Object> runtimeConfig = readRuntimeConfig(entity);
        return new ProviderSummaryResponse(
                entity.getId().toString(),
                entity.getProviderType().name(),
                entity.getCode(),
                entity.getChannel() == null ? "KYC" : entity.getChannel().name(),
                entity.getPriority(),
                entity.isActive(),
                isConfigured(entity, runtimeConfig),
                isConfigurable(entity),
                writeMaskedConfig(runtimeConfig),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ProviderConfigResponse toConfigResponse(ProviderConfigEntity entity, ProviderProfile profile) {
        Map<String, Object> runtimeConfig = readRuntimeConfig(entity);
        return new ProviderConfigResponse(
                entity.getId().toString(),
                profile.code(),
                profile.channel().name(),
                entity.isActive(),
                entity.getPriority(),
                hasRequiredFields(profile, runtimeConfig),
                publicConfig(profile, runtimeConfig)
        );
    }

    private Map<String, Object> publicConfig(ProviderProfile profile, Map<String, Object> runtimeConfig) {
        Map<String, Object> response = new LinkedHashMap<>();

        if (Objects.equals(profile, ROUTE_MOBILE_SMS)) {
            response.put("username", asTrimmedText(runtimeConfig.get("username")));
            response.put("senderId", asTrimmedText(runtimeConfig.get("senderId")));
            response.put("hasPassword", hasText(runtimeConfig.get("password")));
            return response;
        }

        if (Objects.equals(profile, AFRICA_TALKING_SMS)) {
            response.put("username", asTrimmedText(runtimeConfig.get("username")));
            response.put("hasApiKey", hasText(runtimeConfig.get("apiKey")));
            return response;
        }

        if (Objects.equals(profile, WHATSAPP_META)) {
            response.put("phoneNumberId", asTrimmedText(runtimeConfig.get("phoneNumberId")));
            response.put("hasAccessToken", hasText(runtimeConfig.get("accessToken")));
            return response;
        }

        return response;
    }

    private ProviderConfigEntity resolveOrCreate(ProviderProfile profile) {
        return providerConfigRepository
                .findByProviderTypeAndCodeAndChannel(ProviderType.MESSAGING, profile.code(), profile.channel())
                .orElseGet(() -> providerConfigRepository.save(newProvider(profile)));
    }

    private ProviderConfigEntity newProvider(ProviderProfile profile) {
        ProviderConfigEntity entity = new ProviderConfigEntity();
        entity.setProviderType(ProviderType.MESSAGING);
        entity.setCode(profile.code());
        entity.setChannel(profile.channel());
        entity.setPriority(nextPriority(profile.channel()));
        entity.setActive(false);
        entity.setConfigJson("{}");
        return entity;
    }

    private int nextPriority(MessageChannel channel) {
        return providerConfigRepository.findByProviderTypeAndChannelOrderByPriorityAsc(ProviderType.MESSAGING, channel)
                .stream()
                .mapToInt(ProviderConfigEntity::getPriority)
                .max()
                .orElse(0) + 1;
    }

    private ProviderProfile profileFor(String providerCode) {
        String normalized = normalizeProviderCode(providerCode);
        ProviderProfile profile = PROFILES.get(normalized);
        if (profile == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_PROVIDER", "Provider is not configurable from admin: " + normalized);
        }
        return profile;
    }

    private boolean isConfigurable(ProviderConfigEntity entity) {
        return resolveProfile(entity).isPresent();
    }

    private Optional<ProviderProfile> resolveProfile(ProviderConfigEntity entity) {
        ProviderProfile profile = PROFILES.get(entity.getCode());
        if (profile == null || entity.getChannel() == null || profile.channel() != entity.getChannel()) {
            return Optional.empty();
        }
        return Optional.of(profile);
    }

    private boolean isConfigured(ProviderConfigEntity entity, Map<String, Object> runtimeConfig) {
        Optional<ProviderProfile> profile = resolveProfile(entity);
        if (profile.isPresent()) {
            return hasRequiredFields(profile.get(), runtimeConfig);
        }
        if (matches(entity, "ROUTE_MOBILE", MessageChannel.WHATSAPP)) {
            return hasText(runtimeConfig.get("apiKey")) && hasText(runtimeConfig.get("senderId"));
        }
        return entity.getConfigJson() != null && !entity.getConfigJson().isBlank();
    }

    private boolean hasRequiredFields(ProviderProfile profile, Map<String, Object> runtimeConfig) {
        return profile.requiredFields().stream().allMatch(field -> hasText(runtimeConfig.get(field)));
    }

    private void mergeConfig(ProviderProfile profile,
                             Map<String, Object> mergedConfig,
                             Map<String, Object> incomingConfig) {
        if (incomingConfig == null || incomingConfig.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : incomingConfig.entrySet()) {
            String key = entry.getKey();
            if (!profile.allowedFields().contains(key)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_FIELD",
                        "Field is not allowed for provider " + profile.code() + ": " + key);
            }

            String trimmedValue = asTrimmedText(entry.getValue());
            if (profile.secretFields().contains(key)) {
                if (trimmedValue != null && !trimmedValue.isBlank()) {
                    mergedConfig.put(key, trimmedValue);
                }
                continue;
            }

            if (trimmedValue == null || trimmedValue.isBlank()) {
                mergedConfig.remove(key);
            } else {
                mergedConfig.put(key, trimmedValue);
            }
        }
    }

    private void validateRequiredFields(ProviderProfile profile, Map<String, Object> runtimeConfig) {
        if (hasRequiredFields(profile, runtimeConfig)) {
            return;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_CONFIG",
                "Missing required configuration fields for provider " + profile.code());
    }

    private String writeStoredConfig(ProviderProfile profile, Map<String, Object> runtimeConfig) {
        Map<String, Object> stored = new LinkedHashMap<>(runtimeConfig);
        stored.remove("token");

        for (String secretField : profile.secretFields()) {
            String value = asTrimmedText(stored.get(secretField));
            if (value == null || value.isBlank()) {
                stored.remove(secretField);
                continue;
            }
            stored.put(secretField, cryptoService.encrypt(value));
        }

        try {
            return objectMapper.writeValueAsString(stored);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize provider configuration", exception);
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }

    private String writeMaskedConfig(Map<String, Object> runtimeConfig) {
        Map<String, Object> masked = new LinkedHashMap<>(runtimeConfig);
        for (String key : MASKED_KEYS) {
            if (hasText(masked.get(key))) {
                masked.put(key, "***");
            }
        }
        try {
            return objectMapper.writeValueAsString(masked);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private void decryptValue(Map<String, Object> config, String key) {
        String value = asTrimmedText(config.get(key));
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            config.put(key, cryptoService.decrypt(value));
        } catch (Exception exception) {
            config.put(key, value);
        }
    }

    private boolean matches(ProviderConfigEntity entity, String code, MessageChannel channel) {
        return code.equals(entity.getCode()) && channel == entity.getChannel();
    }

    private boolean hasText(Object value) {
        String text = asTrimmedText(value);
        return text != null && !text.isBlank();
    }

    private String asTrimmedText(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private record ProviderProfile(
            String code,
            MessageChannel channel,
            Set<String> requiredFields,
            Set<String> secretFields,
            Set<String> allowedFields
    ) {
    }
}
