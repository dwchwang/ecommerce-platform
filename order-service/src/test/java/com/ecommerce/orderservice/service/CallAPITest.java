package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.dto.product.ProductDto;
import com.ecommerce.orderservice.dto.user.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CallAPITest {

    private MockRestServiceServer server;
    private CallAPI callAPI;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        callAPI = new CallAPI(builder, "http://auth-service:8088/auth", "http://product-service:8086/product");
    }

    @Test
    void callsProductServiceWithContextPath() {
        // TD-06: trước đây thiếu /product → 404
        server.expect(requestTo("http://product-service:8086/product/api/products/1"))
                .andRespond(withSuccess("{\"productId\":1,\"productTitle\":\"iPhone 15\"}", MediaType.APPLICATION_JSON));

        ProductDto product = callAPI.receiverProductDto(1);

        assertThat(product.getProductTitle()).isEqualTo("iPhone 15");
        server.verify();
    }

    @Test
    void callsAuthUserEndpointWithSingleBearerPrefix() {
        // TD-06: trước đây gọi /api/manager/user (không tồn tại)
        server.expect(requestTo("http://auth-service:8088/auth/api/v1/users/7"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer abc"))
                .andRespond(withSuccess("{\"success\":true,\"code\":\"OK\",\"data\":{\"id\":7,\"username\":\"testuser\"}}",
                        MediaType.APPLICATION_JSON));

        UserDto user = callAPI.receiverUserDto(7L, "abc");

        assertThat(user.getUsername()).isEqualTo("testuser");
        server.verify();
    }
}
