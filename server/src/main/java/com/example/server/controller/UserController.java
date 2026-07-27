package com.example.server.controller;

import com.example.server.auth.UserAuthService;
import com.example.server.auth.dto.LoginRequest;
import com.example.server.auth.dto.LoginView;
import com.example.server.auth.dto.RegisterRequest;
import com.example.server.auth.dto.UserView;
import com.example.server.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

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
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            UserView user = userAuthService.register(request);
            result.put("code", 200);
            result.put("msg", "注册成功");
            result.put("data", user);
        } catch (IllegalArgumentException exception) {
            result.put("code", 400);
            result.put("msg", exception.getMessage());
        }
        return result;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            UserView user = userAuthService.authenticate(request);
            LoginView login = new LoginView(jwtService.generateToken(user), user);
            result.put("code", 200);
            result.put("msg", "登录成功");
            result.put("data", login);
        } catch (IllegalArgumentException exception) {
            result.put("code", 401);
            result.put("msg", exception.getMessage());
        }
        return result;
    }
}
