package br.com.argos.argos_api.identity;

import br.com.argos.argos_api.identity.UserManagementService.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserManagementController {

    public record CreateUserRequest(
            @NotBlank(message = "nome é obrigatório") @Size(max = 120) String name,
            @NotBlank(message = "e-mail é obrigatório") @Email(message = "e-mail inválido") @Size(max = 200) String email,
            @NotBlank(message = "senha é obrigatória") @Size(min = 6, max = 120, message = "senha deve ter entre 6 e 120 caracteres") String password,
            @NotNull(message = "papel é obrigatório") Role role) {
    }

    public record ChangeRoleRequest(@NotNull(message = "papel é obrigatório") Role role) {
    }

    public record ChangeStatusRequest(@NotNull(message = "status é obrigatório") Boolean active) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "nova senha é obrigatória") @Size(min = 6, max = 120, message = "senha deve ter entre 6 e 120 caracteres") String password) {
    }

    private final UserManagementService users;

    public UserManagementController(UserManagementService users) {
        this.users = users;
    }

    @GetMapping
    public List<UserSummary> list() {
        return users.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserSummary create(@Valid @RequestBody CreateUserRequest request) {
        return users.create(request.name(), request.email(), request.password(), request.role());
    }

    @PatchMapping("/{id}/role")
    public UserSummary changeRole(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest request) {
        return users.changeRole(id, request.role());
    }

    @PatchMapping("/{id}/status")
    public UserSummary changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest request) {
        return users.changeStatus(id, request.active());
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request) {
        users.resetPassword(id, request.password());
    }
}
