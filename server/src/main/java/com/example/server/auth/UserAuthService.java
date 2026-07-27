package com.example.server.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.auth.dto.LoginRequest;
import com.example.server.auth.dto.RegisterRequest;
import com.example.server.auth.dto.UserView;
import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class UserAuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserAuthService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public UserView register(RegisterRequest request) {
        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.username());
        if (userMapper.selectCount(query) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(StringUtils.hasText(request.nickname()) ? request.nickname() : request.username());
        user.setRole("USER");
        userMapper.insert(user);
        return UserView.from(user);
    }

    public UserView authenticate(LoginRequest request) {
        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.username());
        User user = userMapper.selectOne(query);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        return UserView.from(user);
    }
}
