package com.example.server.service;

public record AuthenticatedUser(
        Long userId,
        String username,
        String role
) {
}
