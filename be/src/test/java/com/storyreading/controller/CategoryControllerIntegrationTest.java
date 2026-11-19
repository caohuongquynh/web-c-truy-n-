package com.storyreading.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyreading.dto.CategoryDto;
import com.storyreading.repository.CategoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Test: gửi HTTP request thật (qua MockMvc) tới CategoryController,
 * chạy xuyên suốt Controller -> Service -> Repository -> database H2 thật sự
 * (không mock gì cả), để kiểm tra các tầng ghép nối với nhau đúng.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CategoryControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CategoryRepository categoryRepository;

    @AfterEach
    void cleanUp() {
        categoryRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /api/categories - không cần đăng nhập vẫn xem được danh sách")
    void getAllCategories_publicAccess_returnsOk() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/categories - chưa đăng nhập -> bị từ chối (403)")
    void createCategory_noAuth_returnsForbidden() throws Exception {
        CategoryDto dto = CategoryDto.builder().name("Kinh Dị").build();

        mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "READER")
    @DisplayName("POST /api/categories - đăng nhập với role READER (không phải Admin) -> bị từ chối (403)")
    void createCategory_wrongRole_returnsForbidden() throws Exception {
        CategoryDto dto = CategoryDto.builder().name("Kinh Dị").build();

        mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/categories - Admin tạo category hợp lệ -> 201 Created, dữ liệu lưu đúng xuống DB")
    void createCategory_asAdmin_createsSuccessfully() throws Exception {
        CategoryDto dto = CategoryDto.builder().name("Kinh Dị").build();

        mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Kinh Dị"))
                .andExpect(jsonPath("$.categoryId").exists());

        // Xác nhận dữ liệu thực sự nằm trong database, không chỉ trả về đúng JSON
        assertCategoryExistsInDb("Kinh Dị");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/categories - trùng tên category đã có -> báo lỗi, không tạo trùng")
    void createCategory_duplicateName_returnsError() throws Exception {
        CategoryDto dto = CategoryDto.builder().name("Phiêu Lưu").build();

        // Tạo lần đầu -> thành công
        mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        // Tạo lần hai với cùng tên -> phải báo lỗi (không tạo bản ghi trùng)
        mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().is5xxServerError());

        long count = categoryRepository.findAll().stream()
                .filter(c -> c.getName().equals("Phiêu Lưu"))
                .count();
        org.junit.jupiter.api.Assertions.assertEquals(1, count, "Không được phép có 2 category cùng tên");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Luồng CRUD đầy đủ: tạo -> đọc -> sửa -> xóa category")
    void fullCrudFlow_createReadUpdateDelete() throws Exception {
        // CREATE
        CategoryDto createDto = CategoryDto.builder().name("Học Đường").build();
        String createResponse = mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(createResponse).get("categoryId").asLong();

        // READ
        mockMvc.perform(get("/api/categories/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Học Đường"));

        // UPDATE
        CategoryDto updateDto = CategoryDto.builder().name("Học Đường (Sửa)").build();
        mockMvc.perform(put("/api/categories/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Học Đường (Sửa)"));

        // DELETE
        mockMvc.perform(delete("/api/categories/" + id))
                .andExpect(status().isNoContent());

        // Xác nhận đã xóa hẳn khỏi DB
        org.junit.jupiter.api.Assertions.assertTrue(categoryRepository.findById(id).isEmpty());
    }

    private void assertCategoryExistsInDb(String name) {
        boolean exists = categoryRepository.findByNameIgnoreCase(name).isPresent();
        org.junit.jupiter.api.Assertions.assertTrue(exists, "Category phải tồn tại trong database sau khi tạo");
    }
}
