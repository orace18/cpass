package com.cpass.platform.common.security;

import com.cpass.platform.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

public final class ClientContextHolder {

    private static final ThreadLocal<ClientPrincipal> CONTEXT = new ThreadLocal<>();

    private ClientContextHolder() {
    }

    public static void set(ClientPrincipal principal) {
        CONTEXT.set(principal);
    }

    public static ClientPrincipal get() {
        return CONTEXT.get();
    }

    public static UUID requireClientId() {
        ClientPrincipal principal = CONTEXT.get();
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Client context missing");
        }
        return principal.clientId();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
