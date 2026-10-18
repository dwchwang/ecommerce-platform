package com.ecommerce.orderservice.service.impl;

import com.ecommerce.commonlib.exception.BusinessException;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.service.CallAPI;
import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrderServiceImplTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final CallAPI callAPI = mock(CallAPI.class);
    private final OrderServiceImpl service = new OrderServiceImpl(orderRepository, new ModelMapper(), callAPI);

    @Test
    void missingOrderIsNotFound() {
        when(orderRepository.findById(999)).thenReturn(Optional.empty());

        // Trước đây ném OrderNotFoundException → handler chung trả 500, nên payment-service
        // không nhận được 404 và TD-02 vẫn lộ ra 500 khi chạy thật (e2e Phase 1).
        assertThatThrownBy(() -> service.findById(999))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(callAPI);
    }
}
