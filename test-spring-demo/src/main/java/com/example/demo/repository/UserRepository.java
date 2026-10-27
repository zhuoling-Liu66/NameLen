package com.example.demo.repository;

import com.example.demo.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA 仓储：用户表基础读写。
 */
public interface UserRepository extends JpaRepository<User, Long> {
}
