package com.ecommerce.inventoryservice.util;

import com.ecommerce.inventoryservice.model.Inventory;
import com.ecommerce.inventoryservice.repository.InventoryRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataLoaderTest {

    private final InventoryRepository repository = mock(InventoryRepository.class);
    private final DataLoader dataLoader = new DataLoader(repository);

    @Test
    void seedsWhenTableIsEmpty() throws Exception {
        when(repository.count()).thenReturn(0L);

        dataLoader.run();

        verify(repository, times(2)).save(any(Inventory.class));
    }

    @Test
    void doesNotSeedAgainOnRestart() throws Exception {
        // TD-03: trước đây mỗi lần khởi động lại chèn thêm một bộ bản ghi trùng
        when(repository.count()).thenReturn(2L);

        dataLoader.run();

        verify(repository, never()).save(any(Inventory.class));
    }
}
