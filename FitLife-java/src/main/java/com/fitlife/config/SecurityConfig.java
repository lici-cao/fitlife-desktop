package com.fitlife.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.ApiResponse;
import com.fitlife.filter.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 安全配置（P1：RBAC + 归属校验前置 + 统一 401/403）。
 *
 * <p>相对原实现的关键变化：
 * <ol>
 *   <li>去掉 {@code /api/ai/**} 的 permitAll——原先未登录即可无限调用大模型、直接消耗额度；</li>
 *   <li>去掉 {@code anyRequest().permitAll()} 这个「兜底全开」，改为默认必须认证；</li>
 *   <li>新增 {@code /api/admin/**} 的 ROLE_ADMIN 约束，并开启方法级注解（@PreAuthorize）；</li>
 *   <li>401/403 统一输出 ApiResponse JSON，前端拦截器可按 code 处理；</li>
 *   <li>CORS 允许来源可通过 {@code app.cors.allowed-origins} 配置，默认 * 以兼容本地开发。</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final ObjectMapper objectMapper;

    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        ApiResponse.unauthorized("未登录或登录已过期")))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                                        ApiResponse.forbidden("没有权限执行该操作"))))
                .authorizeHttpRequests(auth -> auth
                        // 公开：注册登录、健康检查、错误页
                        .requestMatchers("/api/auth/**", "/api/health", "/error").permitAll()
                        // 桌面版：前端产物内嵌在 jar 的 static/ 里，静态资源与 SPA 入口必须匿名可读
                        .requestMatchers("/", "/index.html", "/favicon.svg", "/icons.svg",
                                "/assets/**", "/*.ico", "/*.png", "/*.svg", "/*.css", "/*.js").permitAll()
                        // 动作库详情是只读公共资源（收藏/浏览不需要登录）
                        .requestMatchers(HttpMethod.GET, "/api/exercise/**").permitAll()
                        // 后台管理：仅管理员
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 其余（plan / data / chat / ai）一律要求登录
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeJson(HttpServletResponse response, int status, ApiResponse body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOrigins(origins.isEmpty() ? List.of("*") : origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Idempotent-Replay"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}