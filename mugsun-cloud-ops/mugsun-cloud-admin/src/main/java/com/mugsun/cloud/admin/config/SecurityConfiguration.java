package com.mugsun.cloud.admin.config;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;

/**
 * 监控中心的登录。页面和它背后的接口都要先登录，账号口令是配置里的 {@code spring.security.user}。
 * 不用登录的只有三样：登录页、页面的静态文件、它自己的健康检查（容器的健康检查要请求，它监控自己时也要请求）。
 * 写法照 Spring Boot Admin 文档里 WebFlux 的那一份，和 BladeX 的 blade-admin 一致。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfiguration {

	/** 用它算地址：监控页面可以挂在一个路径前缀下面（{@code spring.boot.admin.context-path}），默认没有前缀 */
	private final AdminServerProperties adminServer;

	public SecurityConfiguration(AdminServerProperties adminServer) {
		this.adminServer = adminServer;
	}

	@Bean
	public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
		return http
			.authorizeExchange(exchanges -> exchanges
				.pathMatchers(adminServer.path("/assets/**"), adminServer.path("/login")).permitAll()
				.pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.anyExchange().authenticated())
			.formLogin(form -> form
				.loginPage(adminServer.path("/login"))
				.authenticationSuccessHandler(new RedirectServerAuthenticationSuccessHandler(adminServer.path("/"))))
			.logout(logout -> logout.logoutUrl(adminServer.path("/logout")))
			.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
			// 页面上的操作是页面里的脚本直接发的请求，不带防跨站的令牌
			.csrf(ServerHttpSecurity.CsrfSpec::disable)
			// 允许被别的页面用内嵌窗口打开：管理后台要把这个页面嵌进去
			.headers(headers -> headers.frameOptions(ServerHttpSecurity.HeaderSpec.FrameOptionsSpec::disable))
			.build();
	}
}
