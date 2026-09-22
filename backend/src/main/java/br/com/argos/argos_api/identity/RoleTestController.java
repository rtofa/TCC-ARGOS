package br.com.argos.argos_api.identity;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/roles-test")
public class RoleTestController {

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminOnly() {
        return "Hello, Admin!";
    }

    @GetMapping("/executive")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXECUTIVE')")
    public String executiveOrAdmin() {
        return "Hello, Executive!";
    }

    @GetMapping("/viewer")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXECUTIVE', 'EDITOR', 'VIEWER')")
    public String viewer() {
        return "Hello, Viewer!";
    }
}
