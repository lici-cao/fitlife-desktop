package com.fitlife.config;

import com.fitlife.entity.User;
import com.fitlife.entity.UserProfile;
import com.fitlife.repository.UserProfileRepository;
import com.fitlife.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 桌面版演示账号初始化器。
 *
 * <p>独立安装包不能要求用户先自己注册一遍才能看到界面，因此首次启动时若用户表为空，
 * 自动创建一个演示账号（demo / 123456）并配好默认档案。
 *
 * <p>可通过 {@code fitlife.desktop.demo-account=false} 关闭。
 * 只做「查无则补」，绝不覆盖用户已有账号。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class DesktopDemoAccountInitializer implements ApplicationRunner {

    private static final String DEMO_USERNAME = "demo";
    private static final String DEMO_PASSWORD = "123456";

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${fitlife.desktop.demo-account:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            if (userRepository.findByUsername(DEMO_USERNAME).isPresent()) {
                return;
            }

            User user = userRepository.save(User.builder()
                    .username(DEMO_USERNAME)
                    .password(passwordEncoder.encode(DEMO_PASSWORD))
                    .build());

            profileRepository.save(UserProfile.builder()
                    .userId(user.getId())
                    .name("演示用户")
                    .gender("female")
                    .age(25)
                    .height(165.0)
                    .weight(60.0)
                    .bodyFat(25.0)
                    .goal("fat_loss")
                    .activityLevel("moderate")
                    .build());

            log.info("已创建演示账号：{} / {}（可在页面内自行注册新账号）", DEMO_USERNAME, DEMO_PASSWORD);
        } catch (Exception e) {
            log.warn("演示账号初始化未完成（不影响使用，可自行注册）：{}", e.getMessage());
        }
    }
}
