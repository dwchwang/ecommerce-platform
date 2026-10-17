package com.ecommerce.paymentservice.service;

import com.ecommerce.commonlib.viewmodel.ApiResponse;
import com.ecommerce.paymentservice.dto.OrderDto;
import com.ecommerce.paymentservice.dto.UserDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CallAPI {

    private final RestClient authClient;
    private final RestClient orderClient;

    // Trước đây dùng http://ORDER-SERVICE kiểu Eureka: Docker DNS vẫn resolve nhưng thiếu port
    // và context-path → gọi vào cổng 80 (TD-06). Mỗi đích một RestClient riêng để không
    // đổi baseUrl trên Builder dùng chung giữa các request đồng thời.
    public CallAPI(RestClient.Builder restClientBuilder,
                   @Value("${services.auth.url}") String authServiceUrl,
                   @Value("${services.order.url}") String orderServiceUrl) {
        this.authClient = restClientBuilder.clone().baseUrl(authServiceUrl).build();
        this.orderClient = restClientBuilder.clone().baseUrl(orderServiceUrl).build();
    }

    public OrderDto receiverPaymentDto(Integer orderId, String token) {
        return orderClient
                .get()
                .uri("/api/orders/{id}", orderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(OrderDto.class);
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
}
