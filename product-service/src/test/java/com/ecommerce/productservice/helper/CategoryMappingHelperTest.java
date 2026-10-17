package com.ecommerce.productservice.helper;

import com.ecommerce.productservice.dto.CategoryDto;
import com.ecommerce.productservice.entity.Category;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryMappingHelperTest {

    @Test
    void rootCategoryDtoMapsToEntityWithoutParent() {
        CategoryDto dto = CategoryDto.builder().categoryTitle("Phone").build();

        Category category = CategoryMappingHelper.map(dto);

        // TD-01: trước đây luôn gán một parent rỗng → Hibernate TransientPropertyValueException
        assertThat(category.getParentCategory()).isNull();
    }

    @Test
    void parentWithoutIdIsTreatedAsNoParent() {
        CategoryDto dto = CategoryDto.builder()
                .categoryTitle("Phone")
                .parentCategoryDto(CategoryDto.builder().build())
                .build();

        assertThat(CategoryMappingHelper.map(dto).getParentCategory()).isNull();
    }

    @Test
    void childCategoryDtoKeepsParentReference() {
        CategoryDto dto = CategoryDto.builder()
                .categoryTitle("Phone")
                .parentCategoryDto(CategoryDto.builder().categoryId(2).build())
                .build();

        Category category = CategoryMappingHelper.map(dto);

        assertThat(category.getParentCategory()).isNotNull();
        assertThat(category.getParentCategory().getCategoryId()).isEqualTo(2);
    }

    @Test
    void rootCategoryEntityMapsToDtoWithoutParent() {
        Category category = Category.builder().categoryId(1).categoryTitle("Root").build();

        assertThat(CategoryMappingHelper.map(category).getParentCategoryDto()).isNull();
    }
}
