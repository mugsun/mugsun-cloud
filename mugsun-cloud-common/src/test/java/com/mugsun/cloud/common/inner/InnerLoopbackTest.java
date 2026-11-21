package com.mugsun.cloud.common.inner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.cloud.common.inner.InnerCallException.Kind;
import com.mugsun.core.tool.exception.ServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 调用方（{@link InnerClient}，用真的 HTTP 客户端）和被调用方（{@link InnerRequestFilter}）在本机回环地址上对接。
 * 两边各自的单元测试验证不了「两边对约定的理解一致」，这里补上：请求头经过 HTTP 传输后签名仍然对得上，中文没有乱码。
 */
class InnerLoopbackTest {

	/** 测试用的假密钥 */
	private static final String SECRET = "test-only-key-0123456789abcdef-XYZ";

	private static final String TARGET = "mugsun-gis";

	private static final String PATH = "/inner/gis/insight/snapshot";

	private final ObjectMapper mapper = new ObjectMapper();

	private final DiscoveryClient discovery = mock(DiscoveryClient.class);

	private final Set<String> seenNonces = new HashSet<>();

	private final List<InnerCall> calls = new ArrayList<>();

	private final List<String> tenantsInside = new ArrayList<>();

	private final List<String> traceIdsReceived = new ArrayList<>();

	private Function<InnerCall, Object> behaviour = call -> {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("layer", call.body().path("layer").asText());
		snapshot.put("features", 128);
		snapshot.put("bbox", List.of(116.2, 39.8, 116.6, 40.1));
		return snapshot;
	};

	/** 服务端写回响应之前可以在这里动手脚，模拟中途被改 */
	private Function<byte[], byte[]> tamper = body -> body;

	private HttpServer server;

	@BeforeEach
	void start() throws IOException {
		server = serve(filter(SECRET));
		when(discovery.getInstances(TARGET)).thenReturn(List.of(instance(server.getAddress().getPort())));
	}

	@AfterEach
	void stop() {
		server.stop(0);
		TenantContext.clear();
	}

	@Test
	void roundTripCarriesTenantBodyAndResult() {
		JsonNode data = TenantContext.execute("T10001",
			() -> client(SECRET).call(TARGET, PATH, Map.of("layer", "北京路网")));

		assertThat(data.path("layer").asText()).isEqualTo("北京路网");
		assertThat(data.path("features").asInt()).isEqualTo(128);
		assertThat(data.path("bbox")).hasSize(4);
		assertThat(calls).hasSize(1);
		assertThat(calls.get(0).caller()).isEqualTo("mugsun-ai");
		assertThat(tenantsInside).containsExactly("T10001");
	}

	@Test
	void businessFailureTravelsBackAsServiceException() {
		behaviour = call -> {
			throw new ServiceException("图层模块没有启用");
		};

		assertThatThrownBy(() -> TenantContext.execute("000000", () -> client(SECRET).call(TARGET, PATH, Map.of())))
			.isInstanceOf(ServiceException.class)
			.hasMessage("图层模块没有启用");
	}

	@Test
	void differentSecretsAreRejectedAndTheEndpointNeverRuns() {
		InnerCallException e = catchThrowableOfType(InnerCallException.class, () -> TenantContext.execute("000000",
			() -> client("another-key-0123456789abcdef-XYZ---").call(TARGET, PATH, Map.of())));

		assertThat(e.kind()).isEqualTo(Kind.REJECTED);
		assertThat(calls).isEmpty();
	}

	@Test
	void replyAlteredOnTheWayIsNotTrusted() {
		tamper = body -> new String(body, StandardCharsets.UTF_8).replace("128", "129").getBytes(StandardCharsets.UTF_8);

		InnerCallException e = catchThrowableOfType(InnerCallException.class,
			() -> TenantContext.execute("000000", () -> client(SECRET).call(TARGET, PATH, Map.of())));

		assertThat(e.kind()).isEqualTo(Kind.FAILED);
		assertThat(e).hasMessageContaining("没有通过验签");
	}

	@Test
	void replyOverTheSizeLimitIsNotRead() {
		behaviour = call -> Map.of("text", "x".repeat(4096));
		InnerProperties properties = properties(SECRET);
		properties.setMaxResponseSize(DataSize.ofBytes(1024));

		assertThatThrownBy(() -> TenantContext.execute("000000", () -> client(properties).call(TARGET, PATH, Map.of())))
			.isInstanceOf(InnerCallException.class)
			.hasMessageContaining("超过上限");
	}

	@Test
	void traceIdReachesTheOtherSide() {
		org.slf4j.MDC.put("traceId", "trace-abc-123");
		try {
			TenantContext.execute("000000", () -> client(SECRET).call(TARGET, PATH, Map.of()));
		} finally {
			org.slf4j.MDC.clear();
		}

		assertThat(traceIdsReceived).containsExactly("trace-abc-123");
	}

	/** 第一个实例已经下线（端口没人听），换另一个实例能成功；无论先挑中哪个，结果都一样 */
	@Test
	void deadInstanceIsSkipped() throws IOException {
		int deadPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			deadPort = socket.getLocalPort();
		}
		when(discovery.getInstances(TARGET)).thenReturn(List.of(instance(deadPort), instance(server.getAddress().getPort())));
		InnerClient client = client(SECRET);

		for (int i = 0; i < 8; i++) {
			JsonNode data = TenantContext.execute("000000", () -> client.call(TARGET, PATH, Map.of("layer", "a")));
			assertThat(data.path("features").asInt()).isEqualTo(128);
		}
		assertThat(calls).hasSize(8);
	}

	@Test
	void slowEndpointTimesOutAndIsReportedAsFailure() {
		behaviour = call -> {
			try {
				Thread.sleep(1500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return Map.of();
		};

		InnerCallException e = catchThrowableOfType(InnerCallException.class, () -> TenantContext.execute("000000",
			() -> client(SECRET).call(TARGET, PATH, Map.of(), Duration.ofMillis(200))));

		assertThat(e.kind()).isEqualTo(Kind.FAILED);
	}

	// ---------- 辅助 ----------

	private static InnerProperties properties(String secret) {
		InnerProperties properties = new InnerProperties();
		properties.setSecret(secret);
		properties.setConnectTimeout(Duration.ofSeconds(2));
		properties.validate();
		return properties;
	}

	private InnerClient client(String secret) {
		return client(properties(secret));
	}

	private InnerClient client(InnerProperties properties) {
		return new InnerClient(properties, discovery, mapper, "mugsun-ai");
	}

	private static DefaultServiceInstance instance(int port) {
		return new DefaultServiceInstance("127.0.0.1:" + port, TARGET, "127.0.0.1", port, false);
	}

	private InnerRequestFilter filter(String secret) {
		InnerNonceStore nonceStore = new InnerNonceStore(null, TARGET) {
			@Override
			public synchronized boolean firstUse(String nonce, Duration ttl) {
				return seenNonces.add(nonce);
			}
		};
		InnerEndpoint endpoint = new InnerEndpoint() {
			@Override
			public String path() {
				return PATH;
			}

			@Override
			public Object handle(InnerCall call) {
				synchronized (calls) {
					calls.add(call);
					tenantsInside.add(TenantContext.getRaw());
				}
				return behaviour.apply(call);
			}
		};
		InnerRequestFilter filter = new InnerRequestFilter(properties(secret), nonceStore, mapper, TARGET,
			() -> List.of(endpoint), System::currentTimeMillis);
		filter.afterSingletonsInstantiated();
		return filter;
	}

	/** 用 JDK 自带的 HTTP 服务器接请求，转成 Servlet 请求交给过滤器，再把过滤器写的响应原样发回去 */
	private HttpServer serve(InnerRequestFilter filter) throws IOException {
		HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		http.createContext("/", exchange -> {
			try {
				handle(filter, exchange);
			} catch (Exception e) {
				exchange.sendResponseHeaders(599, -1);
			} finally {
				exchange.close();
			}
		});
		http.start();
		return http;
	}

	private void handle(InnerRequestFilter filter, HttpExchange exchange) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(exchange.getRequestMethod(),
			exchange.getRequestURI().getRawPath());
		request.setQueryString(exchange.getRequestURI().getRawQuery());
		exchange.getRequestHeaders().forEach((name, values) -> values.forEach(value -> request.addHeader(name, value)));
		request.setContent(exchange.getRequestBody().readAllBytes());
		String traceId = exchange.getRequestHeaders().getFirst("X-Trace-Id");
		if (traceId != null) {
			synchronized (traceIdsReceived) {
				traceIdsReceived.add(traceId);
			}
		}

		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());

		for (String name : response.getHeaderNames()) {
			if (!"Content-Length".equalsIgnoreCase(name)) {
				exchange.getResponseHeaders().add(name, response.getHeader(name));
			}
		}
		byte[] body = tamper.apply(response.getContentAsByteArray());
		exchange.sendResponseHeaders(response.getStatus(), body.length == 0 ? -1 : body.length);
		if (body.length > 0) {
			exchange.getResponseBody().write(body);
		}
	}
}
