package com.ecommerce.orderservice.service;

import com.ecommerce.commonlib.viewmodel.ApiResponse;
import com.ecommerce.orderservice.dto.product.ProductDto;
import com.ecommerce.orderservice.dto.user.UserDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CallAPI {

    private final RestClient authClient;
    private final RestClient productClient;

    // Base URL gồm cả context-path của service đích (TD-06). Mỗi đích một RestClient riêng:
    // gọi baseUrl() trên một Builder dùng chung không an toàn khi có request đồng thời.
    public CallAPI(RestClient.Builder restClientBuilder,
                   @Value("${services.auth.url}") String authServiceUrl,
                   @Value("${services.product.url}") String productServiceUrl) {
        this.authClient = restClientBuilder.clone().baseUrl(authServiceUrl).build();
        this.productClient = restClientBuilder.clone().baseUrl(productServiceUrl).build();
    }

    public UserDto receiverUserDto(Long userId, String token) {
        ApiResponse<UserDto> response = authClient
                .get()
                .uri("/api/v1/users/{id}", userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return response == null ? null : response.data();
    }

    public ProductDto receiverProductDto(Integer productId) {
        return productClient
                .get()
                .uri("/api/products/{id}", productId)
                .retrieve()
                .body(ProductDto.class);
    }
}
