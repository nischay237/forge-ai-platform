package com.forge.platform.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthDtos.AuthResponse register(
        @Valid @RequestBody AuthDtos.RegisterRequest request,
        HttpServletRequest httpRequest
    ) {
        return authService.register(request, resolveClientIp(httpRequest));
    }

    @PostMapping("/login")
    public AuthDtos.AuthResponse login(
        @Valid @RequestBody AuthDtos.LoginRequest request,
        HttpServletRequest httpRequest
    ) {
        return authService.login(request, resolveClientIp(httpRequest));
    }

    @PostMapping("/refresh")
    public AuthDtos.AuthResponse refresh(
        @Valid @RequestBody AuthDtos.RefreshRequest request,
        HttpServletRequest httpRequest
    ) {
        return authService.refreshSession(request, resolveClientIp(httpRequest));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
        @RequestBody(required = false) AuthDtos.LogoutRequest request,
        HttpServletRequest httpRequest
    ) {
        authService.logout(request, resolveClientIp(httpRequest));
    }

    @GetMapping("/me")
    public AuthDtos.SessionResponse me() {
        return authService.currentSession();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
