package com.cpass.platform.admin.service;

import com.cpass.platform.admin.dto.AdminMessageListResponse;
import com.cpass.platform.admin.dto.AdminMessageResponse;
import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.messaging.domain.MessageEntity;
import com.cpass.platform.messaging.domain.MessageStatus;
import com.cpass.platform.messaging.dto.DispatchMessageEvent;
import com.cpass.platform.messaging.infra.MessageRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AdminMessageService {

    private final MessageRepository messageRepository;
    private final ClientRepository clientRepository;
    private final RabbitTemplate rabbitTemplate;
    private final String dispatchExchange;
    private final String dispatchRoutingKey;

    public AdminMessageService(MessageRepository messageRepository,
                               ClientRepository clientRepository,
                               RabbitTemplate rabbitTemplate,
                               @Value("${app.messaging.exchange}") String dispatchExchange,
                               @Value("${app.messaging.routing-key}") String dispatchRoutingKey) {
        this.messageRepository = messageRepository;
        this.clientRepository = clientRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.dispatchExchange = dispatchExchange;
        this.dispatchRoutingKey = dispatchRoutingKey;
    }

    @Transactional(readOnly = true)
    public AdminMessageListResponse listMessages(int page,
                                                 int pageSize,
                                                 String status,
                                                 String provider,
                                                 UUID clientId,
                                                 String from,
                                                 String to) {
        int resolvedPage = Math.max(1, page);
        int resolvedPageSize = Math.min(Math.max(1, pageSize), 100);

        Page<MessageEntity> result = messageRepository.findAll(
                buildSpecification(
                normalizeStatus(status),
                normalizeProvider(provider),
                clientId,
                parseFrom(from),
                parseToExclusive(to)),
                PageRequest.of(resolvedPage - 1, resolvedPageSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        Map<UUID, String> clientNames = clientRepository.findAllById(
                        result.getContent().stream()
                                .map(MessageEntity::getClientId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(ClientEntity::getId, ClientEntity::getName, (left, right) -> left));

        List<AdminMessageResponse> data = result.getContent().stream()
                .map(message -> toResponse(message, clientNames))
                .toList();

        return new AdminMessageListResponse(data, result.getTotalElements(), resolvedPage, resolvedPageSize);
    }

    @Transactional
    public void retryMessage(UUID messageId) {
        MessageEntity message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "Message not found"));

        if (message.getStatus() == MessageStatus.DELIVERED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MESSAGE_ALREADY_DELIVERED", "Delivered messages cannot be retried");
        }

        message.setStatus(MessageStatus.QUEUED);
        message.setProviderCode(null);
        message.setProviderMessageId(null);
        message.setFailureReason(null);
        message.setCost(null);
        messageRepository.save(message);

        rabbitTemplate.convertAndSend(dispatchExchange, dispatchRoutingKey, new DispatchMessageEvent(message.getId()));
    }

    private AdminMessageResponse toResponse(MessageEntity message, Map<UUID, String> clientNames) {
        return new AdminMessageResponse(
                message.getId() == null ? null : message.getId().toString(),
                message.getClientId() == null ? null : message.getClientId().toString(),
                clientNames.getOrDefault(message.getClientId(), "Unknown client"),
                message.getRecipient(),
                message.getChannel() == null ? "UNKNOWN" : message.getChannel().name(),
                toAdminStatus(message.getStatus()),
                message.getProviderCode() == null || message.getProviderCode().isBlank() ? "N/A" : message.getProviderCode(),
                message.getCreatedAt(),
                message.getStatus() == MessageStatus.DELIVERED ? message.getUpdatedAt() : null,
                message.getCost(),
                message.getStatus() == MessageStatus.FAILED ? 1 : 0
        );
    }

    private MessageStatus normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }

        String normalized = status.trim().toUpperCase();
        if ("PENDING".equals(normalized)) {
            return MessageStatus.QUEUED;
        }

        try {
            return MessageStatus.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE_STATUS", "Unsupported message status: " + status);
        }
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }

        String normalized = provider.trim().toUpperCase()
                .replace(' ', '_')
                .replace('-', '_')
                .replace("'", "");

        return switch (normalized) {
            case "ROUTEMOBILE", "ROUTE_MOBILE" -> "ROUTE_MOBILE";
            case "WHATSAPP_BUSINESS", "WHATSAPPMETA", "WHATSAPP_META" -> "WHATSAPP_META";
            case "AFRICAS_TALKING", "AFRICA_TALKING" -> "AFRICA_TALKING";
            default -> normalized;
        };
    }

    private Instant parseFrom(String from) {
        if (from == null || from.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(from.trim()).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FROM_DATE", "Invalid from date: " + from);
        }
    }

    private Instant parseToExclusive(String to) {
        if (to == null || to.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(to.trim()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TO_DATE", "Invalid to date: " + to);
        }
    }

    private String toAdminStatus(MessageStatus status) {
        if (status == null) {
            return "UNKNOWN";
        }
        if (status == MessageStatus.QUEUED) {
            return "PENDING";
        }
        return status.name();
    }

    private Specification<MessageEntity> buildSpecification(MessageStatus status,
                                                            String providerCode,
                                                            UUID clientId,
                                                            Instant fromAt,
                                                            Instant toAt) {
        return Specification.where(hasStatus(status))
                .and(hasProvider(providerCode))
                .and(hasClient(clientId))
                .and(createdAfterOrAt(fromAt))
                .and(createdBefore(toAt));
    }

    private Specification<MessageEntity> hasStatus(MessageStatus status) {
        return (root, query, criteriaBuilder) ->
                status == null ? criteriaBuilder.conjunction() : criteriaBuilder.equal(root.get("status"), status);
    }

    private Specification<MessageEntity> hasProvider(String providerCode) {
        return (root, query, criteriaBuilder) ->
                providerCode == null
                        ? criteriaBuilder.conjunction()
                        : criteriaBuilder.equal(criteriaBuilder.upper(root.get("providerCode")), providerCode);
    }

    private Specification<MessageEntity> hasClient(UUID clientId) {
        return (root, query, criteriaBuilder) ->
                clientId == null ? criteriaBuilder.conjunction() : criteriaBuilder.equal(root.get("clientId"), clientId);
    }

    private Specification<MessageEntity> createdAfterOrAt(Instant fromAt) {
        return (root, query, criteriaBuilder) ->
                fromAt == null ? criteriaBuilder.conjunction() : criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), fromAt);
    }

    private Specification<MessageEntity> createdBefore(Instant toAt) {
        return (root, query, criteriaBuilder) ->
                toAt == null ? criteriaBuilder.conjunction() : criteriaBuilder.lessThan(root.get("createdAt"), toAt);
    }
}
