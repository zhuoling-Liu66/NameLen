# NameLens 链路图测试项目（fixtures）

一个故意覆盖了 NameLens 所有扫描点的迷你 Spring 项目：

| 扫描点 | 对应文件 |
|---|---|
| Spring Security（SecurityFilterChain + requestMatchers） | `config/SecurityConfig.java` |
| Filter（OncePerRequestFilter） | `config/JwtTokenFilter.java` |
| Interceptor（HandlerInterceptor） | `config/LogInterceptor.java` |
| Controller ×2（@RestController + @RequestMapping） | `controller/` |
| Service（@Service + @Transactional） | `service/UserService.java` |
| MyBatis Mapper（@Mapper） | `mapper/UserMapper.java` |
| JPA Repository | `repository/UserRepository.java` |
| Redis 出口（RedisTemplate） | `service/UserService.java` |
| Kafka 出口（KafkaTemplate） | `service/MessageProducer.java` |
| 外部 HTTP 出口（@FeignClient） | `client/PayClient.java` |

依赖**不需要**真正下载成功：NameLens 只解析源码语法与注解名。
但建议在 IDEA 里让 Maven 正常导入，体验更完整。

预期链路图：
前端 → SecurityConfig#securityFilterChain → JwtTokenFilter → DispatcherServlet
     → LogInterceptor → UserController / OrderController → UserService → 数据访问层
     → 数据库 / Redis / Kafka / 外部 HTTP
