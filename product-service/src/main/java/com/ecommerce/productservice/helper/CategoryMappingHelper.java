package com.ecommerce.productservice.helper;

import com.ecommerce.productservice.entity.Category;
import com.ecommerce.productservice.dto.CategoryDto;

public interface CategoryMappingHelper {

    static CategoryDto map(final Category category) {
        final var parentCategory = category.getParentCategory();
        return CategoryDto.builder()
                .categoryId(category.getCategoryId())
                .categoryTitle(category.getCategoryTitle())
                .imageUrl(category.getImageUrl())
                .parentCategoryDto(parentCategory == null ? null :
                        CategoryDto.builder()
                                .categoryId(parentCategory.getCategoryId())
                                .categoryTitle(parentCategory.getCategoryTitle())
                                .imageUrl(parentCategory.getImageUrl())
                                .build()
                )
                .build();
    }

    static Category map(CategoryDto categoryDto) {
        // Chỉ gán parent khi có id: một parent rỗng là entity chưa persist → Hibernate từ chối lưu (TD-01)
        final var parentCategoryDto = categoryDto.getParentCategoryDto();
        final Category parentCategory = parentCategoryDto != null && parentCategoryDto.getCategoryId() != null
                ? Category.builder()
                        .categoryId(parentCategoryDto.getCategoryId())
                        .categoryTitle(parentCategoryDto.getCategoryTitle())
                        .imageUrl(parentCategoryDto.getImageUrl())
                        .build()
                : null;
        return Category.builder()
                .categoryId(categoryDto.getCategoryId())
                .categoryTitle(categoryDto.getCategoryTitle())
                .imageUrl(categoryDto.getImageUrl())
                .parentCategory(parentCategory)
                .build();
    }

}
