package com.ecommerce.inventoryservice;

import com.ecommerce.inventoryservice.repository.InventoryRepository;
import com.ecommerce.inventoryservice.util.DataLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Seed dữ liệu không nhân đôi khi service khởi động lại (TD-03), trên PostgreSQL thật. */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=update") // Liquibase còn placeholder (TD-15)
@Testcontainers
class DataLoaderIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    DataLoader dataLoader;

    @Test
    void seedRunsOnceAcrossRestarts() throws Exception {
        long afterStartup = inventoryRepository.count();
        assertThat(afterStartup).isEqualTo(2);

        dataLoader.run(); // giả lập lần khởi động tiếp theo trên cùng DB

        assertThat(inventoryRepository.count()).isEqualTo(afterStartup);
    }
}
