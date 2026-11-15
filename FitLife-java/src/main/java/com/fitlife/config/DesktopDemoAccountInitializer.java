package com.fitlife.config;

import com.fitlife.entity.User;
import com.fitlife.entity.UserProfile;
import com.fitlife.repository.UserProfileRepository;
import com.fitlife.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 桌面版首次启动时创建一个演示账号，避免「打开就是登录页却登不进去」。
 *
 * <p>行为由 {@code fitlife.desktop.demo-account} 控制：
 * <pre>
 * fitlife:
 *   desktop:
 *     demo-account: false   # 不想暴露演示账号就关掉
 * </pre>
 * 账号：
 * <ul>
 *   <li>普通用户：dandan / 123456</li>
 *   <li>管理员：admin / 123456 （可进入管理端，接口前缀 /api/admin）</li>
 * </ul>
 */
@Component
public class DesktopDemoAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DesktopDemoAccountInitializer.class);

    public static final String DEMO_USERNAME = "dandan";
    public static final String DEMO_PASSWORD = "123456";

    public static final String DEMO_ADMIN_USERNAME = "admin";
    public static final String DEMO_ADMIN_PASSWORD = "123456";

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${fitlife.desktop.demo-account:true}")
    private boolean enabled;

    public DesktopDemoAccountInitializer(UserRepository userRepository,
                                         UserProfileRepository userProfileRepository,
                                         PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("已关闭桌面演示账号初始化（fitlife.desktop.demo-account=false）");
            return;
        }
        ensureDemoAccount(DEMO_USERNAME, DEMO_PASSWORD, User.ROLE_USER, "演示用户");
        ensureDemoAccount(DEMO_ADMIN_USERNAME, DEMO_ADMIN_PASSWORD, User.ROLE_ADMIN, "演示管理员");
    }

    /**
     * 保证指定演示账号存在。
     *
     * <p>若账号已存在但角色不符（例如旧库里已有同名普通账号），只纠正角色，
     * 不覆盖密码与个人资料，避免破坏用户已录入的数据。
     */
    private void ensureDemoAccount(String username, String rawPassword, String role, String profileName) {
        try {
            var existing = userRepository.findByUsername(username);
            if (existing.isPresent()) {
                User user = existing.get();
                if (!role.equals(user.getRole())) {
                    user.setRole(role);
                    userRepository.save(user);
                    log.info("桌面演示账号角色已修正：{} -> {}", username, role);
                } else {
                    log.info("桌面演示账号已存在：{}（role={}）", username, role);
                }
                return;
            }
            User user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(rawPassword));
            user.setRole(role);
            user.setStatus(User.STATUS_ACTIVE);
            user.setCreatedAt(LocalDateTime.now());
            userRepository.save(user);

            UserProfile profile = new UserProfile();
            profile.setUserId(user.getId());
            profile.setName(profileName);
            profile.setGender("male");
            profile.setAge(28);
            profile.setHeight(175.0);
            profile.setWeight(72.0);
            profile.setGoal("减脂");
            profile.setActivityLevel("moderate");
            profile.setExperience("beginner");
            profile.setWeeklyDays(3);
            profile.setSessionMinutes(45);
            profile.setEquipments("哑铃,瑜伽垫");
            profile.setInjuries("");
            profile.setHrRest(68);
            userProfileRepository.save(profile);

            log.info("已创建桌面演示账号：{} / {}（role={}，请首次登录后修改密码）", username, rawPassword, role);
        } catch (Exception e) {
            log.warn("桌面演示账号初始化失败（{}，不影响启动）：{}", username, e.toString());
        }
    }
}
