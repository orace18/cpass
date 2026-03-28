package com.cpass.platform.client.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class ClientScopeConverter implements AttributeConverter<ClientScope, String> {

    @Override
    public String convertToDatabaseColumn(ClientScope attribute) {
        return attribute == null ? null : attribute.scope();
    }

    @Override
    public ClientScope convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return ClientScope.fromScope(dbData);
    }
}
