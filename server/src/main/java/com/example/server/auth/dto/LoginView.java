package com.example.server.auth.dto;

public record LoginView(
        String token,
        UserView user
) {
}
