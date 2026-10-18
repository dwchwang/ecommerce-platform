package com.ecommerce.orderservice;

import com.ecommerce.orderservice.dto.product.ProductDto;
import com.ecommerce.orderservice.service.CallAPI;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Luồng cart → order → đọc lại trên PostgreSQL thật (TD-14).
 * Lời gọi sang product-service được mock — ranh giới giữa service do e2e kiểm.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=update") // Liquibase còn placeholder (TD-15)
@AutoConfigureMockMvc
@Testcontainers
class OrderFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CallAPI callAPI;

    private static final RequestPostProcessor USER = jwt().authorities(new SimpleGrantedAuthority("USER"));

    @Test
    void createCartAndOrderThenReadBackWithProduct() throws Exception {
        when(callAPI.receiverProductDto(7)).thenReturn(ProductDto.builder().productId(7).productTitle("iPhone 16").build());

        String cart = mvc.perform(post("/api/carts").with(USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Integer cartId = JsonPath.read(cart, "$.cartId");

        String order = mvc.perform(post("/api/orders").with(USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderDesc\":\"it\",\"orderFee\":1099,\"productId\":7,\"cart\":{\"cartId\":" + cartId + "}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Integer orderId = JsonPath.read(order, "$.orderId");

        mvc.perform(get("/api/orders/{id}", orderId).with(USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cart.cartId").value(cartId))
                .andExpect(jsonPath("$.product.productTitle").value("iPhone 16"));
    }

    @Test
    void missingOrderIsNotFound() throws Exception {
        // payment-service dựa vào 404 này để từ chối thanh toán (TD-02)
        mvc.perform(get("/api/orders/{id}", 999_999).with(USER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORD-4001"));
    }

    @Test
    void requestWithoutTokenIsRejected() throws Exception {
        mvc.perform(get("/api/orders/{id}", 1)).andExpect(status().isUnauthorized());
    }
}
