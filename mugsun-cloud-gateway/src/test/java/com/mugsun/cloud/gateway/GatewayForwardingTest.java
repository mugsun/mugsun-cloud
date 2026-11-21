package com.mugsun.cloud.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关直接面向客户端（没配可信代理）时的转发行为：路径原样转发、客户端伪造不了转发头、内部接口不对外、
 * 跨域只由网关应答、WebSocket 能转发、后端连不上时按统一格式返回。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false"})
class GatewayForwardingTest extends GatewayTestBase {

	@Test
	void pathAndQueryAreForwardedUnchanged() {
		client().get().uri(raw("/system/user/list?name=%E5%BC%A0&size=10")).exchange().expectStatus().isOk();

		assertThat(RECEIVED).hasSize(1);
		assertThat(RECEIVED.get(0).uri()).isEqualTo("/system/user/list?name=%E5%BC%A0&size=10");
	}

	@Test
	void forwardedHeadersFromClientAreReplaced() {
		client().get().uri(raw("/ping"))
			.header("X-Forwarded-For", "1.1.1.1")
			.header("X-Real-IP", "1.1.1.1")
			.header("Forwarded", "for=1.1.1.1;proto=https")
			.header("X-Forwarded-Proto", "https")
			.header("X-Forwarded-Host", "evil.example.com")
			.header("X-Forwarded-Prefix", "/evil")
			.header("Authorization", "token-value")
			.exchange().expectStatus().isOk();

		Received received = RECEIVED.get(0);
		assertThat(received.header("X-Forwarded-For")).containsExactly("127.0.0.1");
		assertThat(received.header("X-Forwarded-Proto")).containsExactly("http");
		assertThat(received.header("X-Forwarded-Host")).containsExactly("127.0.0.1");
		assertThat(received.header("X-Forwarded-Port")).containsExactly(String.valueOf(gatewayPort));
		assertThat(received.header("X-Real-IP")).isEmpty();
		assertThat(received.header("Forwarded")).isEmpty();
		assertThat(received.header("X-Forwarded-Prefix")).isEmpty();
		assertThat(received.header("Authorization")).containsExactly("token-value");
	}

	@ParameterizedTest
	@ValueSource(strings = {"/inner/message/send", "//inner/message/send", "/%69nner/message/send",
		"/inner;v=1/message/send", "/INNER/message/send", "/%2569nner/message/send"})
	void innerPathsAreNotForwarded(String path) {
		client().post().uri(raw(path)).exchange()
			.expectStatus().isNotFound()
			.expectBody()
			.jsonPath("$.code").isEqualTo(404)
			.jsonPath("$.success").isEqualTo(false)
			.jsonPath("$.msg").isEqualTo("资源不存在");

		assertThat(RECEIVED).isEmpty();
	}

	@Test
	void pathWithParentSegmentIsRejected() {
		client().get().uri(raw("/system/%2e%2e/inner/message/send")).exchange()
			.expectStatus().isBadRequest()
			.expectBody()
			.jsonPath("$.code").isEqualTo(400)
			.jsonPath("$.success").isEqualTo(false)
			.jsonPath("$.msg").isEqualTo("请求地址不合法");

		assertThat(RECEIVED).isEmpty();
	}

	@Test
	void preflightIsAnsweredByGateway() {
		client().method(HttpMethod.OPTIONS).uri(raw("/system/user"))
			.header("Origin", "http://app.example.com")
			.header("Access-Control-Request-Method", "POST")
			.header("Access-Control-Request-Headers", "authorization, content-type")
			.exchange()
			.expectStatus().isOk()
			.expectHeader().valueEquals("Access-Control-Allow-Origin", "http://app.example.com")
			.expectHeader().valueEquals("Access-Control-Allow-Methods", "POST")
			.expectHeader().valueEquals("Access-Control-Allow-Headers", "authorization, content-type")
			.expectHeader().valueEquals("Access-Control-Max-Age", "3600")
			.expectHeader().doesNotExist("Access-Control-Allow-Credentials");

		assertThat(RECEIVED).isEmpty();
	}

	@Test
	void corsHeadersComeFromGatewayOnly() {
		client().get().uri(raw("/system/user"))
			.header("Origin", "http://app.example.com")
			.exchange()
			.expectStatus().isOk()
			.expectHeader().valueEquals("Access-Control-Allow-Origin", "http://app.example.com")
			.expectHeader().doesNotExist("Access-Control-Allow-Credentials");

		assertThat(RECEIVED).hasSize(1);
	}

	@Test
	void websocketIsProxiedWithClientAddress() {
		AtomicReference<String> reply = new AtomicReference<>();
		new ReactorNettyWebSocketClient()
			.execute(URI.create("ws://127.0.0.1:" + gatewayPort + "/ws/message?token=abc"),
				session -> session.send(Mono.just(session.textMessage("ping")))
					.thenMany(session.receive().take(1).map(WebSocketMessage::getPayloadAsText).doOnNext(reply::set))
					.then())
			.block(Duration.ofSeconds(5));

		assertThat(reply.get()).isEqualTo("echo:ping");
		assertThat(RECEIVED).hasSize(1);
		assertThat(RECEIVED.get(0).uri()).isEqualTo("/ws/message?token=abc");
		assertThat(RECEIVED.get(0).header("X-Forwarded-For")).containsExactly("127.0.0.1");
	}

	@Test
	void unreachableBackendGivesBadGatewayInUnifiedFormat() {
		client().get().uri(raw("/dead/anything")).exchange()
			.expectStatus().isEqualTo(502)
			.expectBody()
			.jsonPath("$.code").isEqualTo(502)
			.jsonPath("$.success").isEqualTo(false)
			.jsonPath("$.msg").isEqualTo("服务暂时不可用，请稍后再试");
	}

	@Test
	void serviceWithoutInstanceGivesServiceUnavailable() {
		client().get().uri(raw("/unregistered/anything")).exchange()
			.expectStatus().isEqualTo(503)
			.expectBody()
			.jsonPath("$.code").isEqualTo(503)
			.jsonPath("$.msg").isEqualTo("服务暂时不可用，请稍后再试");
	}

	@Test
	void gatewayHealthIsServedUnderItsOwnPath() {
		client().get().uri(raw("/_gateway/health")).exchange().expectStatus().isOk();
		assertThat(RECEIVED).isEmpty();

		client().get().uri(raw("/actuator/health")).exchange().expectStatus().isOk();
		assertThat(RECEIVED).hasSize(1);
		assertThat(RECEIVED.get(0).uri()).isEqualTo("/actuator/health");
	}
}
