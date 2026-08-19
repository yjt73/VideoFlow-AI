package com.example.server.dto;

public record LoginView(
        String token,
        UserView user
) {
}
