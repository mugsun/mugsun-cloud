package com.mugsun.cloud.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 按客户端地址限制转发：黑名单里的地址不转发，不对外开放的路径只有指定的地址能访问，换写法绕不过去。
 * 测试客户端从 127.0.0.1 连上来，这里把 127.0.0.1 配成可信代理，用 X-Forwarded-For 扮演不同地址的客户端。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false",
	"mugsun.gateway.trusted-proxies=127.0.0.1/32",
	"mugsun.gateway.deny-ips=203.0.113.0/24",
	"mugsun.gateway.restricted-paths=/actuator,/v3/api-docs",
	"mugsun.gateway.restricted-allow-ips=198.51.100.0/24"})
class GatewayClientAccessTest extends GatewayTestBase {

	private static final String DENIED = "203.0.113.9";
	private static final String INSIDER = "198.51.100.7";
	private static final String OUTSIDER = "192.0.2.1";

	@Test
	void deniedAddressGets403InUnifiedFormat() {
		from(DENIED, "/system/user/list").exchange()
			.expectStatus().isForbidden()
			.expectBody()
			.jsonPath("$.code").isEqualTo(403)
			.jsonPath("$.success").isEqualTo(false)
			.jsonPath("$.msg").isEqualTo("当前网络地址不允许访问");

		assertThat(RECEIVED).isEmpty();
	}

	@Test
	void otherAddressesAreForwarded() {
		from(OUTSIDER, "/system/user/list").exchange().expectStatus().isOk();
		from(INSIDER, "/system/user/list").exchange().expectStatus().isOk();

		assertThat(RECEIVED).hasSize(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/actuator", "/actuator/health", "/v3/api-docs", "/v3/api-docs/swagger-config",
		"//actuator/health", "/ACTUATOR/health", "/%61ctuator/health", "/%2561ctuator/health", "/actuator;v=1/health",
		"/actuator%2Fhealth", "/v3;x=1/api-docs"})
	void restrictedPathIsClosedToOutsiders(String path) {
		from(OUTSIDER, path).exchange()
			.expectStatus().isForbidden()
			.expectBody()
			.jsonPath("$.code").isEqualTo(403)
			.jsonPath("$.msg").isEqualTo("这个地址不对外开放");

		assertThat(RECEIVED).isEmpty();
	}

	@Test
	void restrictedPathIsOpenToAllowedAddress() {
		from(INSIDER, "/actuator/health").exchange().expectStatus().isOk();

		assertThat(RECEIVED).hasSize(1);
		assertThat(RECEIVED.get(0).uri()).isEqualTo("/actuator/health");
	}

	@Test
	void pathThatOnlyLooksSimilarIsNotRestricted() {
		from(OUTSIDER, "/actuatorx/health").exchange().expectStatus().isOk();
		from(OUTSIDER, "/system/actuator").exchange().expectStatus().isOk();

		assertThat(RECEIVED).hasSize(2);
	}

	@Test
	void proxyItselfIsAnOutsider() {
		// 不带 X-Forwarded-For：客户端地址就是代理自己的 127.0.0.1，不在能访问的地址里
		client().get().uri(raw("/actuator/health")).exchange().expectStatus().isForbidden();
		client().get().uri(raw("/ping")).exchange().expectStatus().isOk();
	}

	@Test
	void gatewayOwnHealthCheckIsNotAffected() {
		from(DENIED, "/_gateway/health").exchange().expectStatus().isOk();

		assertThat(RECEIVED).isEmpty();
	}

	@Test
	void preflightIsStillAnsweredByGateway() {
		// 跨域预检由网关自己应答，不转发，所以不在限制范围里：它不带数据，应答里只有跨域规则
		client().method(HttpMethod.OPTIONS).uri(raw("/actuator/health"))
			.header("X-Forwarded-For", DENIED)
			.header("Origin", "http://app.example.com")
			.header("Access-Control-Request-Method", "GET")
			.exchange()
			.expectStatus().isOk()
			.expectHeader().valueEquals("Access-Control-Allow-Origin", "http://app.example.com");

		assertThat(RECEIVED).isEmpty();
	}

	@Test
	void websocketFromDeniedAddressIsRefused() {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Forwarded-For", DENIED);
		URI uri = URI.create("ws://127.0.0.1:" + gatewayPort + "/ws/message?token=abc");

		assertThatThrownBy(() -> new ReactorNettyWebSocketClient()
			.execute(uri, headers, session -> Mono.empty()).block(Duration.ofSeconds(5)))
			.hasMessageContaining("403");

		assertThat(RECEIVED).isEmpty();
	}

	private WebTestClient.RequestHeadersSpec<?> from(String clientIp, String rawPath) {
		return client().get().uri(raw(rawPath)).header("X-Forwarded-For", clientIp);
	}
}
