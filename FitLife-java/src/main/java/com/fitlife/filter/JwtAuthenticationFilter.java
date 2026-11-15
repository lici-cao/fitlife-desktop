package com.fitlife.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.ApiResponse;
import com.fitlife.entity.User;
import com.fitlife.repository.UserRepository;
import com.fitlife.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器（P1 改造）。
 *
 * <p>相对原实现的三点变化：
 * <ol>
 *   <li>不再用 {@code Collections.emptyList()} 当权限——按用户 role 装载 ROLE_USER / ROLE_ADMIN，
 *       否则 {@code @PreAuthorize} 与 {@code hasRole} 永远不成立；</li>
 *   <li>账号被禁用（status=DISABLED）时拒绝放行；</li>
 *   <li>401 响应体改成统一的 {@link ApiResponse} JSON，而不是手拼字符串。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        if (!jwtService.validateToken(token)) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiResponse.unauthorized("登录状态已失效，请重新登录"));
            return;
        }

        String username = jwtService.extractUsername(token);
        User user = userRepository.findByUsername(username).orElse(null);

        if (user == null) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiResponse.unauthorized("用户不存在，请重新登录"));
            return;
        }

        if (User.STATUS_DISABLED.equalsIgnoreCase(user.getStatus())) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                    ApiResponse.forbidden("账号已被停用，请联系管理员"));
            return;
        }

        String role = (user.getRole() == null || user.getRole().isBlank())
                ? User.ROLE_USER
                : user.getRole().toUpperCase();

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private void writeJson(HttpServletResponse response, int status, ApiResponse body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}