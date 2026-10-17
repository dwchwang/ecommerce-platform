package com.ecommerce.paymentservice.service.impl;

import com.ecommerce.commonlib.exception.BusinessException;
import com.ecommerce.paymentservice.dto.OrderDto;
import com.ecommerce.paymentservice.dto.PaymentDto;
import com.ecommerce.paymentservice.entity.Payment;
import com.ecommerce.paymentservice.entity.PaymentStatus;
import com.ecommerce.paymentservice.event.EventProducer;
import com.ecommerce.paymentservice.repository.PaymentRepository;
import com.ecommerce.paymentservice.service.CallAPI;
import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentServiceImplTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final CallAPI callAPI = mock(CallAPI.class);
    private final EventProducer eventProducer = mock(EventProducer.class);
    private final PaymentServiceImpl service =
            new PaymentServiceImpl(paymentRepository, new ModelMapper(), callAPI, eventProducer);

    private final PaymentDto request = PaymentDto.builder()
            .orderId(1).userId(1L).isPayed(true).paymentStatus(PaymentStatus.COMPLETED).build();

    @Test
    void alreadyPaidOrderIsRejectedWithConflict() {
        when(paymentRepository.existsByOrderIdAndIsPayed(1)).thenReturn(true);

        // TD-02: trước đây ném PaymentNotFoundException → 500
        assertThatThrownBy(() -> service.save(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(eventProducer);
    }

    @Test
    void paymentForMissingOrderIsRejectedWithNotFound() {
        when(callAPI.receiverPaymentDto(eq(1), anyString()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        // TD-02: trước đây vẫn lưu và vẫn phát event SUCCESSFUL
        assertThatThrownBy(() -> service.save(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    @Test
    void paymentForExistingOrderIsSavedAndPublished() {
        when(callAPI.receiverPaymentDto(eq(1), anyString())).thenReturn(OrderDto.builder().orderId(1).build());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setPaymentId(10);
            return p;
        });

        PaymentDto saved = service.save(request);

        assertThat(saved.getPaymentId()).isEqualTo(10);
        verify(eventProducer).send(anyString(), contains("\"paymentId\":10"));
    }
}
