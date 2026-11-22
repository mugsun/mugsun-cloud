package com.mugsun.cloud.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关前面还有一层可信代理（Nginx）时：采信代理带来的客户端地址和协议，再转交给后端。
 * 测试客户端从 127.0.0.1 连上来，这里把 127.0.0.1 配成可信代理，让它扮演 Nginx。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.nacos.config.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false",
	"mugsun.gateway.trusted-proxies=127.0.0.1/32"})
class GatewayBehindProxyTest extends GatewayTestBase {

	@Test
	void headersFromTrustedProxyAreAdopted() {
		client().get().uri(raw("/ping"))
			.header("X-Forwarded-For", "203.0.113.9")
			.header("X-Forwarded-Proto", "https")
			.header("X-Forwarded-Host", "app.example.com")
			.header("X-Real-IP", "203.0.113.9")
			.exchange().expectStatus().isOk();

		Received received = RECEIVED.get(0);
		assertThat(received.header("X-Forwarded-For")).containsExactly("203.0.113.9");
		assertThat(received.header("X-Forwarded-Proto")).containsExactly("https");
		assertThat(received.header("X-Forwarded-Host")).containsExactly("app.example.com");
		assertThat(received.header("X-Forwarded-Port")).containsExactly("443");
		assertThat(received.header("X-Real-IP")).isEmpty();
	}

	@Test
	void hopAddedByClientBeforeTheProxyIsNotUsed() {
		// 代理用追加的方式写 X-Forwarded-For 时，左边的值是客户端自己填的，不能当客户端地址
		client().get().uri(raw("/ping"))
			.header("X-Forwarded-For", "1.1.1.1, 203.0.113.9")
			.exchange().expectStatus().isOk();

		assertThat(RECEIVED.get(0).header("X-Forwarded-For")).containsExactly("203.0.113.9");
	}

	@Test
	void proxyWithoutForwardedForIsTreatedAsClient() {
		client().get().uri(raw("/ping")).exchange().expectStatus().isOk();

		Received received = RECEIVED.get(0);
		assertThat(received.header("X-Forwarded-For")).containsExactly("127.0.0.1");
		assertThat(received.header("X-Forwarded-Proto")).containsExactly("http");
	}
}
