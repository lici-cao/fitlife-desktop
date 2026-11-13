package com.fitlife.service;

import com.fitlife.entity.IdempotencyRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 幂等记录的持久化操作（P1）。
 *
 * <p>这里刻意不用 JPA，而是直接用 {@link JdbcTemplate}：
 * <ul>
 *   <li>「插入撞唯一键」是幂等机制的正常分支，但在 Hibernate 里一次失败的 flush 会让
 *       persistence context 进入不可用状态，后续同事务内的查询可能抛
 *       {@code don't flush the Session after an exception occurs}；</li>
 *   <li>JDBC 的 INSERT 失败只影响这一条语句，捕获 {@link DuplicateKeyException} 后
 *       可以安全地继续查询既有记录。</li>
 * </ul>
 *
 * <p>表的权威定义见 {@code sql/migration/V1__p1_hardening.sql}；实体
 * {@link IdempotencyRecord} 只用于映射查询结果（以及 ddl-auto=update 时的兜底建表）。
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String SELECT_COLUMNS =
            "id, idem_key, user_id, endpoint, request_hash, status, response_body, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public Optional<IdempotencyRecord> find(Long userId, String idemKey) {
        List<IdempotencyRecord> rows = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM idempotency_key WHERE user_id = ? AND idem_key = ?",
                (rs, rowNum) -> mapRow(rs),
                userId, idemKey);
        return rows.stream().findFirst();
    }

    /**
     * 认领一个幂等键。
     *
     * @return {@code true} 表示认领成功（第一次见到这个 key）；
     *         {@code false} 表示 key 已存在（重复提交或并发请求），由调用方决定回放还是 409
     */
    public boolean claim(Long userId, String idemKey, String endpoint, String requestHash) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotency_key (idem_key, user_id, endpoint, request_hash, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 0, ?, ?)",
                    idemKey, userId, endpoint, requestHash, LocalDateTime.now(), LocalDateTime.now());
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** 业务成功后落盘响应体，供后续同 key 请求回放。 */
    public void markDone(Long userId, String idemKey, String responseBody) {
        jdbcTemplate.update(
                "UPDATE idempotency_key SET status = 1, response_body = ?, updated_at = ? "
                        + "WHERE user_id = ? AND idem_key = ?",
                responseBody, LocalDateTime.now(), userId, idemKey);
    }

    /** 业务失败时释放 key，允许客户端用同一个 key 重试。 */
    public void release(Long userId, String idemKey) {
        jdbcTemplate.update(
                "DELETE FROM idempotency_key WHERE user_id = ? AND idem_key = ?",
                userId, idemKey);
    }

    private IdempotencyRecord mapRow(ResultSet rs) throws SQLException {
        return IdempotencyRecord.builder()
                .id(rs.getLong("id"))
                .idemKey(rs.getString("idem_key"))
                .userId(rs.getLong("user_id"))
                .endpoint(rs.getString("endpoint"))
                .requestHash(rs.getString("request_hash"))
                .status(rs.getInt("status"))
                .responseBody(rs.getString("response_body"))
                .createdAt(rs.getObject("created_at", LocalDateTime.class))
                .updatedAt(rs.getObject("updated_at", LocalDateTime.class))
                .build();
    }
}