package com.mugsun.cloud.gateway;

import com.mugsun.cloud.gateway.support.GatewayClients;
import io.netty.channel.ChannelOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一个服务有两个实例、其中一个连不上时，网关换另一个实例再转发一次，客户端收不到失败；
 * 已经连上以后的失败不重试。
 *
 * <p>实例列表写在配置里，不连注册中心。网关轮流选实例，所以一个接一个地发请求时，正好一半先选到坏的那个。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false"})
class GatewayNextInstanceTest {

	private static final AtomicInteger LIVE_HITS = new AtomicInteger();

	private static final AtomicInteger BROKEN_HITS = new AtomicInteger();

	private static final AtomicInteger RESET_HITS = new AtomicInteger();

	/** 好的实例：把收到的请求方法、请求体长度和内容摘要报回来 */
	private static final DisposableServer LIVE = HttpServer.create().host("127.0.0.1").port(0)
		.handle((request, response) -> {
			LIVE_HITS.incrementAndGet();
			return response.sendString(request.receive().aggregate().asString(StandardCharsets.UTF_8)
				.defaultIfEmpty("").map(body -> echoOf(request.method().name(), body)));
		})
		.bindNow();

	/** 连得上、但收完请求不应答就断开的实例 */
	private static final DisposableServer BROKEN = HttpServer.create().host("127.0.0.1").port(0)
		.handle((request, response) -> request.receive().aggregate().then(Mono.defer(() -> {
			BROKEN_HITS.incrementAndGet();
			response.withConnection(connection -> connection.channel().close());
			return Mono.<Void>never();
		})))
		.bindNow();

	/** 连得上、但收完请求就把连接重置的实例：进程被强杀时，正在等它应答的一方看到的就是这个 */
	private static final DisposableServer RESET = HttpServer.create().host("127.0.0.1").port(0)
		.handle((request, response) -> request.receive().aggregate().then(Mono.defer(() -> {
			RESET_HITS.incrementAndGet();
			response.withConnection(connection -> {
				// SO_LINGER 设成 0 再关：内核直接发 RST，不走正常的关闭流程
				connection.channel().config().setOption(ChannelOption.SO_LINGER, 0);
				connection.channel().close();
			});
			return Mono.<Void>never();
		})))
		.bindNow();

	@LocalServerPort
	int gatewayPort;

	@DynamicPropertySource
	static void routes(DynamicPropertyRegistry registry) {
		String dead = "http://127.0.0.1:" + closedPort();
		String live = "http://127.0.0.1:" + LIVE.port();
		String broken = "http://127.0.0.1:" + BROKEN.port();
		service(registry, 0, "half-dead", dead, live);
		service(registry, 1, "all-dead", dead);
		service(registry, 2, "half-broken", broken, live);
		service(registry, 3, "half-reset", "http://127.0.0.1:" + RESET.port(), live);
	}

	private static void service(DynamicPropertyRegistry registry, int index, String name, String... instances) {
		String route = "spring.cloud.gateway.server.webflux.routes[" + index + "]";
		registry.add(route + ".id", () -> name);
		registry.add(route + ".uri", () -> "lb://" + name);
		registry.add(route + ".predicates[0]", () -> "Path=/" + name + "/**");
		for (int i = 0; i < instances.length; i++) {
			String uri = instances[i];
			registry.add("spring.cloud.discovery.client.simple.instances." + name + "[" + i + "].uri", () -> uri);
		}
	}

	@BeforeEach
	void resetCounters() {
		LIVE_HITS.set(0);
		BROKEN_HITS.set(0);
		RESET_HITS.set(0);
	}

	@Test
	void unreachableInstanceIsSkipped() {
		for (int i = 0; i < 6; i++) {
			client().get().uri("/half-dead/ping").exchange()
				.expectStatus().isOk()
				.expectBody(String.class).isEqualTo(echoOf("GET", ""));
		}

		assertThat(LIVE_HITS).hasValue(6);
	}

	/** 请求体没有先存进内存：第一次没连上时它还没被读过，换实例以后要完整地发过去 */
	@Test
	void requestBodyArrivesIntactAfterSwitchingInstance() {
		String body = "0123456789abcdef".repeat(20_000);

		for (int i = 0; i < 6; i++) {
			client().post().uri("/half-dead/upload").bodyValue(body).exchange()
				.expectStatus().isOk()
				.expectBody(String.class).isEqualTo(echoOf("POST", body));
		}

		assertThat(LIVE_HITS).hasValue(6);
	}

	@Test
	void stillBadGatewayWhenThereIsNoOtherInstance() {
		client().get().uri("/all-dead/ping").exchange()
			.expectStatus().isEqualTo(502)
			.expectBody()
			.jsonPath("$.code").isEqualTo(502)
			.jsonPath("$.success").isEqualTo(false)
			.jsonPath("$.msg").isEqualTo("服务暂时不可用，请稍后再试");
	}

	/** 请求已经发到了对方、对方没应答就断开：对方可能已经执行过，不能再发给别的实例 */
	@Test
	void failureAfterConnectingIsNotRetried() {
		List<Integer> statuses = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			statuses.add(client().post().uri("/half-broken/save").bodyValue("data").exchange()
				.returnResult(String.class).getStatus().value());
		}

		assertThat(statuses).containsExactlyInAnyOrder(200, 200, 502, 502);
		assertThat(LIVE_HITS).hasValue(2);
		assertThat(BROKEN_HITS.get()).isGreaterThanOrEqualTo(2);
	}

	/** 请求发到对方以后连接被重置：和对方不应答就断开一样，返回 502，不重试 */
	@Test
	void connectionResetIsBadGatewayAndNotRetried() {
		List<Integer> statuses = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			statuses.add(client().post().uri("/half-reset/save").bodyValue("data").exchange()
				.returnResult(String.class).getStatus().value());
		}

		assertThat(statuses).containsExactlyInAnyOrder(200, 200, 502, 502);
		assertThat(LIVE_HITS).hasValue(2);
		assertThat(RESET_HITS.get()).isGreaterThanOrEqualTo(2);
	}

	private static String echoOf(String method, String body) {
		return method + " " + body.length() + " " + body.hashCode();
	}

	private WebTestClient client() {
		return GatewayClients.onPort(gatewayPort);
	}

	private static int closedPort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
