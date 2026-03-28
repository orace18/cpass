package com.cpass.platform.user.controller;

import com.cpass.platform.common.security.UserContextHolder;
import com.cpass.platform.user.dto.BasicResponse;
import com.cpass.platform.user.dto.ChangePasswordRequest;
import com.cpass.platform.user.dto.UserResponse;
import com.cpass.platform.user.service.UserAuthService;
import com.cpass.platform.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me")
@PreAuthorize("hasAnyRole('ADMIN','CLIENT')")
public class ProfileController {

    private final UserService userService;
    private final UserAuthService userAuthService;

    public ProfileController(UserService userService,
                             UserAuthService userAuthService) {
        this.userService = userService;
        this.userAuthService = userAuthService;
    }

    @GetMapping
    public UserResponse me() {
        return userService.currentUser(UserContextHolder.requireUser());
    }

    @PutMapping("/password")
    public BasicResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return userAuthService.changePassword(UserContextHolder.requireUser(), request);
    }
}
