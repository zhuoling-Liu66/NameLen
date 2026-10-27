package com.example.demo.service;

import com.example.demo.mapper.UserMapper;
import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * 用户业务：热点用户走 Redis 缓存，未命中再查库，并发送注册事件到 Kafka。
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MessageProducer messageProducer;

    public UserService(UserRepository userRepository,
                       UserMapper userMapper,
                       RedisTemplate<String, Object> redisTemplate,
                       MessageProducer messageProducer) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.redisTemplate = redisTemplate;
        this.messageProducer = messageProducer;
    }

    @Transactional
    public User createUser(User user) {
        User saved = userRepository.save(user);
        messageProducer.sendUserRegistered(saved.getId());
        return saved;
    }

    public User findById(Long id) {
        String key = "user:hot:" + id;
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof User u) {
            return u;
        }
        User user = userMapper.selectById(id);
        if (user != null) {
            redisTemplate.opsForValue().set(key, user, Duration.ofMinutes(10));
        }
        return user;
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }
}
