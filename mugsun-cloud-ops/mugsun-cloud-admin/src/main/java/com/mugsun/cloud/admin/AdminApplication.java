package com.mugsun.cloud.admin;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 监控中心启动入口：一个页面看到网关和各服务的每个实例在不在线，用的是 Spring Boot Admin。
 *
 * <p>要看哪些实例不用配：它到 Nacos 的服务登记里查，登记了的实例都会出现在页面上，包括它自己。
 * 在不在线是它定时去请求每个实例的健康检查地址得来的，直接连实例，不经网关。
 * 做法和 BladeX 的 blade-admin 一样：页面要先登录，账号口令写在配置里。
 */
@EnableAdminServer
@SpringBootApplication
public class AdminApplication {

	public static void main(String[] args) {
		SpringApplication.run(AdminApplication.class, args);
	}
}
