package com.fitlife.service;

import com.fitlife.dto.ApiResponse;
import com.fitlife.dto.LoginRequest;
import com.fitlife.dto.RegisterRequest;
import com.fitlife.entity.User;
import com.fitlife.entity.UserProfile;
import com.fitlife.repository.UserProfileRepository;
import com.fitlife.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public ApiResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            return ApiResponse.error("用户名已存在");
        }

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();
        userRepository.save(user);

        // 创建默认档案
        UserProfile profile = UserProfile.builder()
                .userId(user.getId())
                .name("")
                .gender("female")
                .age(25)
                .height(165.0)
                .weight(60.0)
                .bodyFat(25.0)
                .goal("fat_loss")
                .activityLevel("moderate")
                .build();
        profileRepository.save(profile);

        String token = jwtService.generateToken(user);
        return ApiResponse.success("注册成功", Map.of(
                "token", token,
                "user", Map.of("id", user.getId(), "username", user.getUsername())
        ));
    }

    public ApiResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return ApiResponse.error("用户名或密码错误");
        }

        String token = jwtService.generateToken(user);
        return ApiResponse.success("登录成功", Map.of(
                "token", token,
                "user", Map.of("id", user.getId(), "username", user.getUsername())
        ));
    }
}
