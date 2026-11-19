package com.storyreading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterRequest {
    @NotBlank
    private String username;

    @NotBlank
    private String password;

    @NotBlank
    @Email
    private String email;

    @NotNull
    private LocalDate birthDate;

    // Optional — only "READER" or "CREATOR" are honored (see UserServiceImpl.register).
    // Anything else (including "ADMIN") is ignored and falls back to READER, so self-registration
    // can never be used to grant admin rights.
    private String role;
}
