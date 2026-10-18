package com.ecommerce.paymentservice;

import com.ecommerce.paymentservice.constant.KafkaConstant;
import com.ecommerce.paymentservice.dto.OrderDto;
import com.ecommerce.paymentservice.service.CallAPI;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Payment trên PostgreSQL (container) + Kafka (broker nhúng) (TD-14): lưu, phát event, chặn trùng (409), chặn order không tồn tại (404).
 * Lời gọi sang order-service được mock.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=update", // Liquibase còn placeholder (TD-15)
        // Producer tự cấu hình đọc thẳng property này (@Value)
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@AutoConfigureMockMvc
@Testcontainers
// Broker KRaft chạy trong JVM thay vì container: KafkaContainer của Testcontainers 1.21 không
// khởi động được với Docker Desktop 29 (advertised listener rơi về 0.0.0.0).
@EmbeddedKafka(partitions = 1, topics = KafkaConstant.STATUS_PAYMENT_SUCCESSFUL)
class PaymentFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    EmbeddedKafkaBroker kafka;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CallAPI callAPI;

    private static final RequestPostProcessor USER = jwt().authorities(new SimpleGrantedAuthority("USER"));

    private Consumer<String, String> consumer;

    @BeforeEach
    void subscribe() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(KafkaConstant.STATUS_PAYMENT_SUCCESSFUL));
        // Chờ được gán partition rồi mới gửi, nếu không "latest" sẽ bỏ qua message vừa gửi
        while (consumer.assignment().isEmpty()) {
            consumer.poll(Duration.ofMillis(200));
        }
    }

    @AfterEach
    void close() {
        consumer.close();
    }

    @Test
    void paymentIsSavedPublishedAndDuplicateIsRejected() throws Exception {
        when(callAPI.receiverPaymentDto(eq(41), any())).thenReturn(OrderDto.builder().orderId(41).build());

        mvc.perform(post("/api/payments").with(USER).contentType(MediaType.APPLICATION_JSON).content(body(41)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").isNumber());

        assertThat(eventsWithin(Duration.ofSeconds(15)))
                .singleElement().satisfies(v -> assertThat(v).contains("\"orderId\":41"));

        mvc.perform(post("/api/payments").with(USER).contentType(MediaType.APPLICATION_JSON).content(body(41)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAY-5003"));
        assertThat(eventsWithin(Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void paymentForMissingOrderIsRejectedWithoutEvent() throws Exception {
        when(callAPI.receiverPaymentDto(eq(999), any()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        mvc.perform(post("/api/payments").with(USER).contentType(MediaType.APPLICATION_JSON).content(body(999)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORD-4001"));
        assertThat(eventsWithin(Duration.ofSeconds(3))).isEmpty();
    }

    private static String body(int orderId) {
        return "{\"orderId\":" + orderId + ",\"userId\":1,\"isPayed\":true,\"paymentStatus\":\"COMPLETED\"}";
    }

    private List<String> eventsWithin(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> values = new java.util.ArrayList<>();
        while (System.nanoTime() < deadline && values.isEmpty()) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
            StreamSupport.stream(records.spliterator(), false).map(ConsumerRecord::value).forEach(values::add);
        }
        return values;
    }
}
