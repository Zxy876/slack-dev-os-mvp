package com.asyncaiflow.web;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.asyncaiflow.support.RequestIdFilter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;

@Schema(description = "Unified API response envelope")
public record Result<T>(
        @Schema(description = "Business code", example = "OK")
        String code,
        @Schema(description = "Human readable message", example = "success")
        String message,
        @Schema(description = "Request trace identifier", example = "req_9f3dfe4f4d6a4b8b9f3dfe4f4d6a4b8b")
        String requestId,
        @Schema(description = "Business payload")
        T data
) {

    public static <T> Result<T> ok(T data) {
        return ok("success", data);
    }

    public static <T> Result<T> ok(String message, T data) {
        return new Result<>("OK", message, currentRequestId(), data);
    }

    public static <T> Result<T> error(String code, String message) {
        return new Result<>(code, message, currentRequestId(), null);
    }

    private static String currentRequestId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            HttpServletRequest request = servletRequestAttributes.getRequest();
            Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
            if (requestId instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return "req_unavailable";
    }
}