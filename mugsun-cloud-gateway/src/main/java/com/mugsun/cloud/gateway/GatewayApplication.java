package com.mugsun.cloud.gateway;

import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 网关启动入口。对外只暴露网关一个端口，请求按路径分到各服务，路由写在 application.yml。
 *
 * <p>登录和权限由各服务自己校验（和 mugsun-boot 一样用 Sa-Token），网关不重复做。网关负责两件事：
 * 把真实的客户端地址交给后面的服务，以及挡掉服务之间互相调用的内部接口。
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayGuardProperties.class)
public class GatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(GatewayApplication.class, args);
	}
}
