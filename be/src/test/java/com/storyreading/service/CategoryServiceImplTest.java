package com.storyreading.service;

import com.storyreading.dto.CategoryDto;
import com.storyreading.mapper.CategoryMapper;
import com.storyreading.model.Category;
import com.storyreading.repository.CategoryRepository;
import com.storyreading.service.impl.CategoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private CategoryMapper categoryMapper;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    @Test
    @DisplayName("Tạo category với tên chưa tồn tại -> thành công")
    void createCategory_newName_success() {
        CategoryDto dto = CategoryDto.builder().name("Kiếm Hiệp").build();
        Category saved = Category.builder().categoryId(1L).name("Kiếm Hiệp").build();

        when(categoryRepository.findByNameIgnoreCase("Kiếm Hiệp")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(Category.class))).thenReturn(saved);
        when(categoryMapper.toDto(saved)).thenReturn(CategoryDto.builder().categoryId(1L).name("Kiếm Hiệp").build());

        CategoryDto result = categoryService.createCategory(dto);

        assertThat(result.getCategoryId()).isEqualTo(1L);
        verify(categoryRepository).save(any(Category.class));
    }

    @Test
    @DisplayName("Tạo category trùng tên (không phân biệt hoa/thường) -> ném IllegalArgumentException")
    void createCategory_duplicateName_throwsException() {
        CategoryDto dto = CategoryDto.builder().name("kiếm hiệp").build();
        Category existing = Category.builder().categoryId(1L).name("Kiếm Hiệp").build();

        when(categoryRepository.findByNameIgnoreCase("kiếm hiệp")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> categoryService.createCategory(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Cập nhật category giữ nguyên tên của chính nó -> không bị coi là trùng")
    void updateCategory_sameNameAsItself_success() {
        Category existing = Category.builder().categoryId(1L).name("Ngôn Tình").build();
        CategoryDto dto = CategoryDto.builder().name("Ngôn Tình").build();

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findByNameIgnoreCase("Ngôn Tình")).thenReturn(Optional.of(existing));
        when(categoryRepository.save(any(Category.class))).thenReturn(existing);
        when(categoryMapper.toDto(existing)).thenReturn(dto);

        CategoryDto result = categoryService.updateCategory(1L, dto);

        assertThat(result.getName()).isEqualTo("Ngôn Tình");
    }

    @Test
    @DisplayName("Cập nhật category sang tên đã bị category KHÁC sử dụng -> ném IllegalArgumentException")
    void updateCategory_nameTakenByAnotherCategory_throwsException() {
        Category toUpdate = Category.builder().categoryId(1L).name("Ngôn Tình").build();
        Category another = Category.builder().categoryId(2L).name("Kiếm Hiệp").build();
        CategoryDto dto = CategoryDto.builder().name("Kiếm Hiệp").build();

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(toUpdate));
        when(categoryRepository.findByNameIgnoreCase("Kiếm Hiệp")).thenReturn(Optional.of(another));

        assertThatThrownBy(() -> categoryService.updateCategory(1L, dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("Xóa category không tồn tại -> ném IllegalArgumentException")
    void deleteCategory_notFound_throwsException() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.deleteCategory(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }
}
