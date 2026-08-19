package com.example.server.controller;

import com.example.server.service.UserAuthService;
import com.example.server.api.ApiResponse;
import com.example.server.dto.LoginRequest;
import com.example.server.dto.LoginView;
import com.example.server.dto.RegisterRequest;
import com.example.server.dto.UserView;
import com.example.server.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class UserController {

    private final UserAuthService userAuthService;
    private final JwtService jwtService;

    public UserController(UserAuthService userAuthService, JwtService jwtService) {
        this.userAuthService = userAuthService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ApiResponse<UserView> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(userAuthService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginView> login(@Valid @RequestBody LoginRequest request) {
        UserView user = userAuthService.authenticate(request);
        return ApiResponse.success(new LoginView(jwtService.generateToken(user), user));
    }
}
