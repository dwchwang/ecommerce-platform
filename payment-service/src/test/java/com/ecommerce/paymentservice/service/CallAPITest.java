package com.ecommerce.paymentservice.service;

import com.ecommerce.paymentservice.dto.OrderDto;
import com.ecommerce.paymentservice.dto.UserDto;
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
        callAPI = new CallAPI(builder, "http://auth-service:8088/auth", "http://order-service:8084/order");
    }

    @Test
    void callsOrderServiceWithPortAndContextPath() {
        // TD-06: trước đây là http://ORDER-SERVICE/api/orders/1 → cổng 80
        server.expect(requestTo("http://order-service:8084/order/api/orders/1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer abc"))
                .andRespond(withSuccess("{\"orderId\":1}", MediaType.APPLICATION_JSON));

        OrderDto order = callAPI.receiverPaymentDto(1, "abc");

        assertThat(order.getOrderId()).isEqualTo(1);
        server.verify();
    }

    @Test
    void callsAuthUserEndpointAndUnwrapsApiResponse() {
        server.expect(requestTo("http://auth-service:8088/auth/api/v1/users/7"))
                .andRespond(withSuccess("{\"success\":true,\"code\":\"OK\",\"data\":{\"id\":7,\"username\":\"testuser\"}}",
                        MediaType.APPLICATION_JSON));

        UserDto user = callAPI.receiverUserDto(7L, "abc");

        assertThat(user.getUsername()).isEqualTo("testuser");
        server.verify();
    }
}
