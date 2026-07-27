package com.example.server.auth;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.server.auth.dto.LoginRequest;
import com.example.server.auth.dto.RegisterRequest;
import com.example.server.auth.dto.UserView;
import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAuthServiceTest {

    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private UserAuthService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordEncoder = new BCryptPasswordEncoder();
        service = new UserAuthService(userMapper, passwordEncoder);
    }

    @Test
    void registerHashesPasswordAndReturnsSanitizedView() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        UserView view = service.register(new RegisterRequest("rick", "plain-password", "Rick"));

        var userCaptor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        User storedUser = userCaptor.getValue();
        assertThat(storedUser.getPassword()).isNotEqualTo("plain-password");
        assertThat(passwordEncoder.matches("plain-password", storedUser.getPassword())).isTrue();
        assertThat(view.toString()).doesNotContain(storedUser.getPassword());
    }

    @Test
    void bcryptUsesDifferentSaltForTheSamePassword() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        service.register(new RegisterRequest("rick-one", "plain-password", null));
        service.register(new RegisterRequest("rick-two", "plain-password", null));

        var userCaptor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper, org.mockito.Mockito.times(2)).insert(userCaptor.capture());
        assertThat(userCaptor.getAllValues().get(0).getPassword())
                .isNotEqualTo(userCaptor.getAllValues().get(1).getPassword());
    }

    @Test
    void authenticateAcceptsCorrectPasswordAndRejectsWrongPassword() {
        User storedUser = user("rick", passwordEncoder.encode("plain-password"));
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(storedUser);

        assertThat(service.authenticate(new LoginRequest("rick", "plain-password")).username())
                .isEqualTo("rick");
        assertThatThrownBy(() -> service.authenticate(new LoginRequest("rick", "wrong-password")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userViewNeverContainsPassword() {
        User storedUser = user("rick", passwordEncoder.encode("plain-password"));

        assertThat(UserView.from(storedUser).toString()).doesNotContain(storedUser.getPassword());
    }

    private User user(String username, String password) {
        User user = new User();
        user.setId(1L);
        user.setUsername(username);
        user.setPassword(password);
        user.setNickname("Rick");
        user.setRole("USER");
        return user;
    }
}
