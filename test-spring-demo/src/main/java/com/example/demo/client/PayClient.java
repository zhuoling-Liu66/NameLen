package com.example.demo.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 支付服务客户端：调用外部支付系统（演示 HTTP 出口节点）。
 */
@FeignClient(name = "pay-service", url = "http://pay.example.com")
public interface PayClient {

    @PostMapping("/pay")
    String pay(@RequestParam("orderId") Long orderId);
}
