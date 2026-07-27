package com.example.server.auth.dto;

import com.example.server.entity.User;

public record UserView(
        Long id,
        String username,
        String nickname,
        String avatar,
        String role
) {

    public static UserView from(User user) {
        return new UserView(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatar(),
                user.getRole()
        );
    }
}
