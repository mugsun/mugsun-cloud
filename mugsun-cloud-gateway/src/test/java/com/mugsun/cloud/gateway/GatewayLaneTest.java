package com.mugsun.cloud.gateway;

import com.mugsun.cloud.gateway.lane.LaneRuleBook;
import com.mugsun.cloud.gateway.support.GatewayClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 灰度发布经过网关的完整过程：按规则给请求定灰度标记，按标记选实例，把标记写进发给服务的请求头，记进访问日志。
 *
 * <p>实例列表写在配置里，不连注册中心；灰度实例就是元数据里有 lane 的实例。假后端有三个，应答的内容是「自己是谁|收到的灰度请求头」。
 * 测试里的请求都从本机发给网关：把本机配成可信代理，用 X-Forwarded-For、X-Forwarded-Host 来当不同的客户端、访问不同的域名。
 * 规则直接送进 {@link LaneRuleBook}，和 Nacos 送来时走的是同一个入口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.nacos.config.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false",
	"mugsun.gateway.trusted-proxies=127.0.0.1/32"})
@ExtendWith(OutputCaptureExtension.class)
class GatewayLaneTest {

	private static final String LANE_HEADER = "X-Mugsun-Lane";

	/** 在按地址的规则里点了名的客户端 */
	private static final String TESTER = "203.0.113.7";

	/** 哪条规则都对不上的客户端 */
	private static final String STRANGER = "198.51.100.1";

	private static final String TESTER_GOES_CANARY = "lanes:\n  - name: canary\n    client-ips: " + TESTER + "\n";

	private static final DisposableServer BASE = backend("base");

	private static final DisposableServer CANARY = backend("canary");

	private static final DisposableServer BETA = backend("beta");

	/** 假后端：普通请求和 WebSocket（路径以 /ws 结尾）都应答「自己是谁|收到的灰度请求头」，没收到头写 - */
	private static DisposableServer backend(String name) {
		return HttpServer.create().host("127.0.0.1").port(0)
			.handle((request, response) -> {
				List<String> lanes = request.requestHeaders().getAll(LANE_HEADER);
				String reply = name + "|" + (lanes.isEmpty() ? "-" : String.join(",", lanes));
				if (request.uri().endsWith("/ws")) {
					return response.sendWebsocket((inbound, outbound) ->
						outbound.sendString(inbound.receive().asString().map(text -> reply)));
				}
				return response.sendString(Mono.just(reply));
			})
			.bindNow();
	}

	/** 一个实例：在哪个地址上，带什么灰度标记（正式实例是 null） */
	private record Instance(String uri, String lane) {

		static Instance of(DisposableServer server, String lane) {
			return new Instance("http://127.0.0.1:" + server.port(), lane);
		}

		/** 登记在注册中心里、但已经连不上的实例 */
		static Instance unreachable(String lane) {
			return new Instance("http://127.0.0.1:" + closedPort(), lane);
		}
	}

	@LocalServerPort
	int gatewayPort;

	@Autowired
	LaneRuleBook book;

	@DynamicPropertySource
	static void routes(DynamicPropertyRegistry registry) {
		// 正在灰度的服务：一个正式实例，两个标记各一个灰度实例
		service(registry, 0, "mixed", Instance.of(BASE, null), Instance.of(CANARY, "canary"), Instance.of(BETA, "beta"));
		// 这次发版没动的服务：只有正式实例
		service(registry, 1, "plain", Instance.of(BASE, null));
		// 灰度实例挂了，正式实例还在
		service(registry, 2, "grey-down", Instance.unreachable("canary"), Instance.of(BASE, null));
		// 没有正式实例：两个灰度实例，其中一个挂了
		service(registry, 3, "grey-only", Instance.unreachable("canary"), Instance.of(CANARY, "canary"));
		// 正式实例挂了，灰度实例还在
		service(registry, 4, "base-down", Instance.unreachable(null), Instance.of(CANARY, "canary"));
	}

	private static void service(DynamicPropertyRegistry registry, int index, String name, Instance... instances) {
		String route = "spring.cloud.gateway.server.webflux.routes[" + index + "]";
		registry.add(route + ".id", () -> name);
		registry.add(route + ".uri", () -> "lb://" + name);
		registry.add(route + ".predicates[0]", () -> "Path=/" + name + "/**");
		for (int i = 0; i < instances.length; i++) {
			Instance instance = instances[i];
			String prefix = "spring.cloud.discovery.client.simple.instances." + name + "[" + i + "]";
			registry.add(prefix + ".uri", instance::uri);
			if (instance.lane() != null) {
				registry.add(prefix + ".metadata.lane", instance::lane);
			}
		}
	}

	@AfterEach
	void clearRules() {
		book.load(null);
	}

	private void rules(String content) {
		assertThat(book.load(content).status()).as("这份规则要能换上").isEqualTo(LaneRuleBook.Status.UPDATED);
	}

	@Test
	void withoutRulesNoRequestReachesAGreyInstanceWhateverHeaderTheClientSends() {
		for (int i = 0; i < 6; i++) {
			assertThat(reply(from(TESTER, "/mixed/ping").header(LANE_HEADER, "canary"))).isEqualTo("base|-");
		}
	}

	@Test
	void clientListedByAddressIsServedByTheGreyInstancesOfItsLane() {
		rules(TESTER_GOES_CANARY);

		for (int i = 0; i < 4; i++) {
			assertThat(reply(from(TESTER, "/mixed/ping"))).isEqualTo("canary|canary");
			assertThat(reply(from(STRANGER, "/mixed/ping"))).isEqualTo("base|-");
		}
	}

	@Test
	void visitorOfAListedHostIsServedByTheGreyInstancesOfItsLane() {
		rules("lanes:\n  - name: beta\n    hosts: beta.example.com\n");

		for (int i = 0; i < 4; i++) {
			assertThat(reply(from(STRANGER, "/mixed/ping").header("X-Forwarded-Host", "beta.example.com")))
				.isEqualTo("beta|beta");
			assertThat(reply(from(STRANGER, "/mixed/ping").header("X-Forwarded-Host", "www.example.com")))
				.isEqualTo("base|-");
		}
	}

	/**
	 * 按比例：同一个客户端每次的结果都一样；把比例调大，原来在灰度里的还在。
	 * 这两个地址在 canary 这个标记下算出来的数是 4801 和 5044（算法见 LaneRules），所以 50% 时一个在一个不在，51% 时都在。
	 */
	@Test
	void percentageDrawsTheSameClientsEveryTimeAndKeepsThemWhenRaised() {
		rules("lanes:\n  - name: canary\n    percent: 50\n");
		for (int i = 0; i < 3; i++) {
			assertThat(reply(from("203.0.113.10", "/mixed/ping"))).isEqualTo("canary|canary");
			assertThat(reply(from("203.0.113.1", "/mixed/ping"))).isEqualTo("base|-");
		}

		rules("lanes:\n  - name: canary\n    percent: 51\n");
		assertThat(reply(from("203.0.113.10", "/mixed/ping"))).isEqualTo("canary|canary");
		assertThat(reply(from("203.0.113.1", "/mixed/ping"))).isEqualTo("canary|canary");

		rules("lanes:\n  - name: canary\n    percent: 0\n");
		assertThat(reply(from("203.0.113.10", "/mixed/ping"))).isEqualTo("base|-");
	}

	/** 写进 header-from 的地址（测试人员、压测工具）可以用请求头自己指定标记；别人带这个头没有用 */
	@Test
	void listedAddressesMayPickTheLaneWithTheHeader() {
		rules("lanes:\n  - name: canary\n    client-ips: 203.0.113.99\nheader-from: 203.0.113.0/24\n");

		assertThat(reply(from(TESTER, "/mixed/ping").header(LANE_HEADER, "canary"))).isEqualTo("canary|canary");
		assertThat(reply(from(TESTER, "/mixed/ping").header(LANE_HEADER, "beta"))).isEqualTo("beta|beta");
		assertThat(reply(from(TESTER, "/mixed/ping"))).isEqualTo("base|-");
		assertThat(reply(from(STRANGER, "/mixed/ping").header(LANE_HEADER, "canary"))).isEqualTo("base|-");
		// 写法不对的值、不止一个值：当成没带
		assertThat(reply(from(TESTER, "/mixed/ping").header(LANE_HEADER, "Canary!"))).isEqualTo("base|-");
		assertThat(reply(from(TESTER, "/mixed/ping").header(LANE_HEADER, "canary", "beta"))).isEqualTo("base|-");
		// 规则本来会让它走灰度的客户端，说 base 就是这次不走
		assertThat(reply(from("203.0.113.99", "/mixed/ping"))).isEqualTo("canary|canary");
		assertThat(reply(from("203.0.113.99", "/mixed/ping").header(LANE_HEADER, "base"))).isEqualTo("base|-");
	}

	/** 这个服务没有灰度实例：正式实例处理，标记照样传下去，它再调别的服务时还能找到那边的灰度实例 */
	@Test
	void laneIsPassedOnThroughAServiceThatHasNoGreyInstance() {
		rules(TESTER_GOES_CANARY);

		for (int i = 0; i < 3; i++) {
			assertThat(reply(from(TESTER, "/plain/ping"))).isEqualTo("base|canary");
		}
		assertThat(reply(from(STRANGER, "/plain/ping"))).isEqualTo("base|-");
	}

	@Test
	void unreachableGreyInstanceIsReplacedByABaseInstance() {
		rules(TESTER_GOES_CANARY);

		for (int i = 0; i < 4; i++) {
			assertThat(reply(from(TESTER, "/grey-down/ping"))).isEqualTo("base|canary");
		}
	}

	/** 没有正式实例可换时，才换同一个标记的另一个灰度实例。网关轮流选实例，连着发的请求里一半先选到挂了的那个 */
	@Test
	void withoutBaseInstancesAnotherGreyInstanceOfTheLaneIsTried() {
		rules(TESTER_GOES_CANARY);

		for (int i = 0; i < 4; i++) {
			assertThat(reply(from(TESTER, "/grey-only/ping"))).isEqualTo("canary|canary");
		}
	}

	/** 没有标记的请求宁可失败，也不发给灰度实例：正式实例一个都没有是 503，有但连不上是 502 */
	@Test
	void requestWithoutALaneFailsRatherThanReachAGreyInstance(CapturedOutput output) {
		rules(TESTER_GOES_CANARY);

		for (int i = 0; i < 3; i++) {
			from(STRANGER, "/grey-only/ping").exchange().expectStatus().isEqualTo(503);
			from(STRANGER, "/base-down/ping").exchange().expectStatus().isEqualTo(502);
		}

		assertThat(output.getOut()).contains("grey-only 在注册中心里的 2 个实例都是灰度实例，没有灰度标记的请求不能发给它们，返回 503");
		// 带着标记的请求照常
		assertThat(reply(from(TESTER, "/base-down/ping"))).isEqualTo("canary|canary");
	}

	@Test
	void accessLogTellsWhichRequestsWentGrey(CapturedOutput output) {
		rules(TESTER_GOES_CANARY);

		reply(from(TESTER, "/mixed/ping").header("X-Trace-Id", "lane-log-1"));
		reply(from(STRANGER, "/mixed/ping").header("X-Trace-Id", "lane-log-2"));
		reply(from(TESTER, "/plain/ping").header("X-Trace-Id", "lane-log-3"));
		reply(from(TESTER, "/grey-down/ping").header("X-Trace-Id", "lane-log-4"));

		assertThat(accessLine(output, "lane-log-1"))
			.contains(" route=mixed to=127.0.0.1:" + CANARY.port() + " lane=canary end=ok trace=lane-log-1");
		assertThat(accessLine(output, "lane-log-2"))
			.contains(" route=mixed to=127.0.0.1:" + BASE.port() + " lane=- end=ok trace=lane-log-2");
		// 带着标记，处理它的却是正式实例：这个服务没有灰度实例，或者灰度实例没连上
		assertThat(accessLine(output, "lane-log-3"))
			.contains(" route=plain to=127.0.0.1:" + BASE.port() + " lane=canary>base end=ok trace=lane-log-3");
		assertThat(accessLine(output, "lane-log-4"))
			.contains(" route=grey-down to=127.0.0.1:" + BASE.port() + " lane=canary>base end=ok trace=lane-log-4");
	}

	/** 改错了的规则整份不收，正在进行的灰度不受影响；规则清空以后，下一个请求就不再走灰度 */
	@Test
	void brokenRulesChangeNothingAndClearedRulesStopTheGreyTrafficAtOnce() {
		rules(TESTER_GOES_CANARY);

		assertThat(book.load("lanes:\n  - name: canary\n    client-ips: " + STRANGER + "\n    precent: 100\n").status())
			.isEqualTo(LaneRuleBook.Status.REJECTED);
		assertThat(reply(from(TESTER, "/mixed/ping"))).isEqualTo("canary|canary");
		assertThat(reply(from(STRANGER, "/mixed/ping"))).isEqualTo("base|-");

		book.load("");
		assertThat(reply(from(TESTER, "/mixed/ping"))).isEqualTo("base|-");
	}

	/** WebSocket 握手也一样：按规则定标记、选灰度实例、把标记写进握手请求的头；客户端自己带的头不算 */
	@Test
	void websocketHandshakeFollowsTheSameRules() {
		rules(TESTER_GOES_CANARY);

		assertThat(overWebSocket(TESTER, null)).isEqualTo("canary|canary");
		assertThat(overWebSocket(STRANGER, "canary")).isEqualTo("base|-");
	}

	private WebTestClient.RequestHeadersSpec<?> from(String clientIp, String path) {
		return GatewayClients.onPort(gatewayPort).get().uri(path).header("X-Forwarded-For", clientIp);
	}

	/** 请求要成功；返回假后端应答的内容：「谁处理的|它收到的灰度请求头」 */
	private static String reply(WebTestClient.RequestHeadersSpec<?> request) {
		return request.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
	}

	private String overWebSocket(String clientIp, String laneHeader) {
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-Forwarded-For", clientIp);
		if (laneHeader != null) {
			headers.add(LANE_HEADER, laneHeader);
		}
		AtomicReference<String> reply = new AtomicReference<>();
		new ReactorNettyWebSocketClient()
			.execute(URI.create("ws://127.0.0.1:" + gatewayPort + "/mixed/ws"), headers,
				session -> session.send(Mono.just(session.textMessage("ping")))
					.thenMany(session.receive().take(1).map(WebSocketMessage::getPayloadAsText).doOnNext(reply::set))
					.then())
			.block(Duration.ofSeconds(5));
		return reply.get();
	}

	/** 访问日志在响应写完之后才记，客户端可能先一步拿到响应，所以等一会儿 */
	private static String accessLine(CapturedOutput output, String traceId) {
		AtomicReference<String> found = new AtomicReference<>();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			Optional<String> line = output.getOut().lines()
				.filter(text -> text.contains(" ACCESS ") && text.endsWith("trace=" + traceId))
				.findFirst();
			assertThat(line).as("访问日志里 trace=%s 的那一行", traceId).isPresent();
			found.set(line.get());
		});
		return found.get();
	}

	private static int closedPort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
