package com.example.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口：演示第二个 Controller。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
