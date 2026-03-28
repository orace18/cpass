package com.cpass.platform.admin.controller;

import com.cpass.platform.admin.dto.AdminMessageListResponse;
import com.cpass.platform.admin.service.AdminMessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/messages")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMessageController {

    private final AdminMessageService adminMessageService;

    public AdminMessageController(AdminMessageService adminMessageService) {
        this.adminMessageService = adminMessageService;
    }

    @GetMapping
    public AdminMessageListResponse listMessages(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String provider,
                                                 @RequestParam(required = false) UUID clientId,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        return adminMessageService.listMessages(page, pageSize, status, provider, clientId, from, to);
    }

    @PostMapping("/{messageId}/retry")
    public ResponseEntity<Void> retryMessage(@PathVariable UUID messageId) {
        adminMessageService.retryMessage(messageId);
        return ResponseEntity.accepted().build();
    }
}
