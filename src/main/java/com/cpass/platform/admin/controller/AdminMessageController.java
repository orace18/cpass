package com.cpass.platform.admin.controller;

import com.cpass.platform.admin.dto.AdminMessageListResponse;
import com.cpass.platform.admin.service.AdminMessageService;
import com.cpass.platform.common.security.AdminSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/messages")
public class AdminMessageController {

    private final AdminMessageService adminMessageService;
    private final AdminSecurityService adminSecurityService;

    public AdminMessageController(AdminMessageService adminMessageService,
                                  AdminSecurityService adminSecurityService) {
        this.adminMessageService = adminMessageService;
        this.adminSecurityService = adminSecurityService;
    }

    @GetMapping
    public AdminMessageListResponse listMessages(@RequestHeader("X-ADMIN-TOKEN") String headerToken,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String provider,
                                                 @RequestParam(required = false) UUID clientId,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        adminSecurityService.ensureAdminToken(headerToken);
        return adminMessageService.listMessages(page, pageSize, status, provider, clientId, from, to);
    }

    @PostMapping("/{messageId}/retry")
    public ResponseEntity<Void> retryMessage(@RequestHeader("X-ADMIN-TOKEN") String headerToken,
                                             @PathVariable UUID messageId) {
        adminSecurityService.ensureAdminToken(headerToken);
        adminMessageService.retryMessage(messageId);
        return ResponseEntity.accepted().build();
    }
}
