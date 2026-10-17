package com.ecommerce.orderservice.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class JwtTokenFilter {

    private JwtTokenFilter() {
    }

    public static String getTokenFromRequest() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthenticationToken)) {
            return "";
        }
        // Trả token thô; CallAPI tự thêm "Bearer " (trước đây bị lặp thành "Bearer Bearer …")
        return jwtAuthenticationToken.getToken().getTokenValue();
    }
}
