package com.cpass.platform.admin.dto;

import java.util.List;

public record AdminMessageListResponse(
        List<AdminMessageResponse> data,
        long total,
        int page,
        int pageSize
) {
}
