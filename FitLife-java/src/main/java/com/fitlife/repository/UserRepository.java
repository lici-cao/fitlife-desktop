package com.fitlife.repository;

import com.fitlife.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 后台用户列表：按用户名模糊搜索，空关键字由 Service 换成 findAll(pageable)。 */
    Page<User> findByUsernameContainingIgnoreCase(String keyword, Pageable pageable);
}