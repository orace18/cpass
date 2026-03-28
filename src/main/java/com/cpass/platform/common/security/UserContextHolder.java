package com.cpass.platform.common.security;

import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.user.domain.UserRole;
import org.springframework.http.HttpStatus;

import java.util.UUID;

public final class UserContextHolder {

    private static final ThreadLocal<UserPrincipal> CONTEXT = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(UserPrincipal principal) {
        CONTEXT.set(principal);
    }

    public static UserPrincipal get() {
        return CONTEXT.get();
    }

    public static UserPrincipal requireUser() {
        UserPrincipal principal = CONTEXT.get();
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "User context missing");
        }
        return principal;
    }

    public static void requireAdmin() {
        if (requireUser().role() != UserRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Admin role required");
        }
    }

    public static UUID requireClientId() {
        UserPrincipal principal = requireUser();
        if (principal.clientId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CLIENT_CONTEXT_MISSING", "Client context missing");
        }
        return principal.clientId();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
