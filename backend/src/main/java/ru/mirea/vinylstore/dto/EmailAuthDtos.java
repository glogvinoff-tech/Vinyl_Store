package ru.mirea.vinylstore.dto;
import jakarta.validation.constraints.*;
import ru.mirea.vinylstore.dto.AuthDtos.ClientType;
public final class EmailAuthDtos {
    public enum Purpose { LOGIN, REGISTER }
    public record CodeRequest(
        @NotBlank @Email @Size(max=254) @Pattern(regexp="(?i)^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@gmail\\.com$",message="Укажите адрес Gmail") String email,
        @NotNull Purpose purpose,
        @Size(max=160) String displayName,
        @Size(max=128) String password) {}
    public record VerifyRequest(
        @NotBlank @Email @Size(max=254) String email,
        @NotNull Purpose purpose,
        @NotBlank @Pattern(regexp="[0-9]{6}") String code,
        ClientType clientType) {}
    public record CodeSent(String message,int retryAfterSeconds,int expiresInSeconds) {}
}
