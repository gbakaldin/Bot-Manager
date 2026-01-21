package com.vingame.bot.domain.environment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.environment.dto.EnvironmentDTO;
import com.vingame.bot.domain.environment.mapper.EnvironmentMapper;
import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.model.EnvironmentFilter;
import com.vingame.bot.domain.environment.model.EnvironmentType;
import com.vingame.bot.domain.environment.model.ProductCode;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@WebMvcTest(EnvironmentController.class)
@DisplayName("EnvironmentController")
class EnvironmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EnvironmentService service;

    @MockitoBean
    private EnvironmentMapper mapper;

    @Nested
    @DisplayName("GET /api/v1/environment/{id}")
    class GetByIdTests {

        @Test
        @DisplayName("Should return 200 OK when environment exists")
        void shouldReturnOkWhenEnvironmentExists() throws Exception {
            // Arrange
            String envId = "test-env-123";
            Environment environment = Environment.builder()
                    .id(envId)
                    .name("Test Environment")
                    .type(EnvironmentType.DEVELOPMENT)
                    .brandCode(BrandCode.G0)
                    .productCode(ProductCode.P_097)
                    .build();

            EnvironmentDTO dto = EnvironmentDTO.builder()
                    .id(envId)
                    .name("Test Environment")
                    .type(EnvironmentType.DEVELOPMENT)
                    .brandCode(BrandCode.G0)
                    .productCode(ProductCode.P_097)
                    .build();

            when(service.findById(envId)).thenReturn(environment);
            when(mapper.toDTO(environment)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(get("/api/v1/environment/{id}", envId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(envId))
                    .andExpect(jsonPath("$.name").value("Test Environment"))
                    .andExpect(jsonPath("$.type").value("DEVELOPMENT"))
                    .andExpect(jsonPath("$.brandCode").value("G0"))
                    .andExpect(jsonPath("$.productCode").value("097"));
        }

        @Test
        @DisplayName("Should return 404 Not Found when environment does not exist")
        void shouldReturnNotFoundWhenEnvironmentDoesNotExist() throws Exception {
            // Arrange
            String envId = "non-existent";
            when(service.findById(envId)).thenThrow(new ResourceNotFoundException("Environment not found"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/environment/{id}", envId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when ID is malformed")
        void shouldReturnBadRequestWhenIdIsMalformed() throws Exception {
            // Arrange
            String envId = "bad-id";
            when(service.findById(envId)).thenThrow(new IllegalArgumentException("Invalid ID"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/environment/{id}", envId))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/environment/")
    class GetAllTests {

        @Test
        @DisplayName("Should return 200 OK with list of environments")
        void shouldReturnOkWithListOfEnvironments() throws Exception {
            // Arrange
            Environment env1 = Environment.builder()
                    .id("1")
                    .name("Env 1")
                    .type(EnvironmentType.PRODUCTION)
                    .build();

            Environment env2 = Environment.builder()
                    .id("2")
                    .name("Env 2")
                    .type(EnvironmentType.STAGING)
                    .build();

            EnvironmentDTO dto1 = EnvironmentDTO.builder()
                    .id("1")
                    .name("Env 1")
                    .type(EnvironmentType.PRODUCTION)
                    .build();

            EnvironmentDTO dto2 = EnvironmentDTO.builder()
                    .id("2")
                    .name("Env 2")
                    .type(EnvironmentType.STAGING)
                    .build();

            when(service.findAll()).thenReturn(List.of(env1, env2));
            when(mapper.toDTO(env1)).thenReturn(dto1);
            when(mapper.toDTO(env2)).thenReturn(dto2);

            // Act & Assert
            mockMvc.perform(get("/api/v1/environment/"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value("1"))
                    .andExpect(jsonPath("$[1].id").value("2"));
        }

        @Test
        @DisplayName("Should return 200 OK with empty list when no environments exist")
        void shouldReturnOkWithEmptyListWhenNoEnvironmentsExist() throws Exception {
            // Arrange
            when(service.findAll()).thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(get("/api/v1/environment/"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/environment/filter/")
    class FilterTests {

        @Test
        @DisplayName("Should return 200 OK with filtered environments by brand code")
        void shouldReturnOkWithFilteredEnvironmentsByBrandCode() throws Exception {
            // Arrange
            EnvironmentFilter filter = new EnvironmentFilter();
            filter.setBrandCode(BrandCode.G0);

            Environment env = Environment.builder()
                    .id("1")
                    .name("G0 Environment")
                    .brandCode(BrandCode.G0)
                    .build();

            EnvironmentDTO dto = EnvironmentDTO.builder()
                    .id("1")
                    .name("G0 Environment")
                    .brandCode(BrandCode.G0)
                    .build();

            when(service.filter(any(EnvironmentFilter.class))).thenReturn(List.of(env));
            when(mapper.toDTO(env)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(post("/api/v1/environment/filter/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(filter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].brandCode").value("G0"));
        }

        @Test
        @DisplayName("Should return 200 OK with filtered environments by product code")
        void shouldReturnOkWithFilteredEnvironmentsByProductCode() throws Exception {
            // Arrange
            EnvironmentFilter filter = new EnvironmentFilter();
            filter.setProductCode(ProductCode.P_097);

            Environment env = Environment.builder()
                    .id("1")
                    .name("097 Environment")
                    .productCode(ProductCode.P_097)
                    .build();

            EnvironmentDTO dto = EnvironmentDTO.builder()
                    .id("1")
                    .name("097 Environment")
                    .productCode(ProductCode.P_097)
                    .build();

            when(service.filter(any(EnvironmentFilter.class))).thenReturn(List.of(env));
            when(mapper.toDTO(env)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(post("/api/v1/environment/filter/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(filter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].productCode").value("097"));
        }

        @Test
        @DisplayName("Should return 200 OK with empty list when no matches")
        void shouldReturnOkWithEmptyListWhenNoMatches() throws Exception {
            // Arrange
            EnvironmentFilter filter = new EnvironmentFilter();
            filter.setType(EnvironmentType.PRODUCTION);

            when(service.filter(any(EnvironmentFilter.class))).thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(post("/api/v1/environment/filter/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(filter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/environment/")
    class CreateTests {

        @Test
        @DisplayName("Should return 200 OK with created environment")
        void shouldReturnOkWithCreatedEnvironment() throws Exception {
            // Arrange
            EnvironmentDTO inputDto = EnvironmentDTO.builder()
                    .name("New Environment")
                    .type(EnvironmentType.DEVELOPMENT)
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_103)
                    .build();

            Environment entity = Environment.builder()
                    .name("New Environment")
                    .type(EnvironmentType.DEVELOPMENT)
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_103)
                    .build();

            Environment savedEntity = Environment.builder()
                    .id("generated-id-123")
                    .name("New Environment")
                    .type(EnvironmentType.DEVELOPMENT)
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_103)
                    .build();

            EnvironmentDTO outputDto = EnvironmentDTO.builder()
                    .id("generated-id-123")
                    .name("New Environment")
                    .type(EnvironmentType.DEVELOPMENT)
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_103)
                    .build();

            when(mapper.toEntity(any(EnvironmentDTO.class))).thenReturn(entity);
            when(service.save(entity)).thenReturn(savedEntity);
            when(mapper.toDTO(savedEntity)).thenReturn(outputDto);

            // Act & Assert
            mockMvc.perform(post("/api/v1/environment/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(inputDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("generated-id-123"))
                    .andExpect(jsonPath("$.name").value("New Environment"))
                    .andExpect(jsonPath("$.brandCode").value("G2"))
                    .andExpect(jsonPath("$.productCode").value("103"));
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/environment/{id}")
    class UpdateTests {

        @Test
        @DisplayName("Should return 200 OK with updated environment")
        void shouldReturnOkWithUpdatedEnvironment() throws Exception {
            // Arrange
            String envId = "env-123";
            EnvironmentDTO updateDto = EnvironmentDTO.builder()
                    .name("Updated Name")
                    .build();

            Environment updatedEntity = Environment.builder()
                    .id(envId)
                    .name("Updated Name")
                    .type(EnvironmentType.PRODUCTION)
                    .build();

            EnvironmentDTO outputDto = EnvironmentDTO.builder()
                    .id(envId)
                    .name("Updated Name")
                    .type(EnvironmentType.PRODUCTION)
                    .build();

            when(service.update(eq(envId), any(EnvironmentDTO.class))).thenReturn(updatedEntity);
            when(mapper.toDTO(updatedEntity)).thenReturn(outputDto);

            // Act & Assert
            mockMvc.perform(patch("/api/v1/environment/{id}", envId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(envId))
                    .andExpect(jsonPath("$.name").value("Updated Name"));
        }

        @Test
        @DisplayName("Should return 404 Not Found when environment does not exist")
        void shouldReturnNotFoundWhenEnvironmentDoesNotExist() throws Exception {
            // Arrange
            String envId = "non-existent";
            EnvironmentDTO updateDto = EnvironmentDTO.builder()
                    .name("Updated Name")
                    .build();

            when(service.update(eq(envId), any(EnvironmentDTO.class)))
                    .thenThrow(new ResourceNotFoundException("Environment not found"));

            // Act & Assert
            mockMvc.perform(patch("/api/v1/environment/{id}", envId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/environment/{id}")
    class DeleteTests {

        @Test
        @DisplayName("Should return 200 OK when environment is deleted")
        void shouldReturnOkWhenEnvironmentIsDeleted() throws Exception {
            // Arrange
            String envId = "env-123";
            doNothing().when(service).delete(envId);

            // Act & Assert
            mockMvc.perform(delete("/api/v1/environment/{id}", envId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 404 Not Found when environment does not exist")
        void shouldReturnNotFoundWhenEnvironmentDoesNotExist() throws Exception {
            // Arrange
            String envId = "non-existent";
            doThrow(new IllegalArgumentException("Not found")).when(service).delete(envId);

            // Act & Assert
            mockMvc.perform(delete("/api/v1/environment/{id}", envId))
                    .andExpect(status().isNotFound());
        }
    }
}
