package com.mugsun.cloud.common.inner;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.cloud.common.inner.InnerCallException.Kind;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InnerClientTest {

	/** 测试用的假密钥 */
	private static final String SECRET = "test-only-key-0123456789abcdef-XYZ";

	private static final byte[] KEY = SECRET.getBytes(StandardCharsets.UTF_8);

	private static final String SELF = "mugsun-ai";

	private static final String TARGET = "mugsun-gis";

	private static final String PATH = "/inner/gis/insight/snapshot";

	private static final long NOW = 1_700_000_000_000L;

	private static final String OK_REPLY = "{\"ok\":true,\"data\":{\"count\":3}}";

	private static final String BUSY_REPLY = "{\"ok\":false,\"code\":\"busy\",\"msg\":\"这个内部接口正忙，这次没有执行\"}";

	/** 熔断计时用的假时钟（毫秒），测试自己往前拨 */
	private final AtomicLong ticker = new AtomicLong();

	private final ObjectMapper mapper = new ObjectMapper();

	private final DiscoveryClient discovery = mock(DiscoveryClient.class);

	private final List<Sent> sent = new ArrayList<>();

	private Responder responder = request -> signedOk(request, "{\"ok\":true,\"data\":{\"count\":3}}");

	private record Sent(URI uri, Map<String, String> headers, byte[] body, Duration readTimeout) {

		String nonce() {
			return headers.get(InnerProtocol.HEADER_NONCE);
		}
	}

	private interface Responder {

		InnerClient.Reply reply(Sent request) throws IOException;
	}

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
		MDC.clear();
	}

	// ---------- 发出去的请求 ----------

	@Test
	void sendsSignedRequestCarryingTheCurrentTenant() throws Exception {
		oneInstance();

		JsonNode data = TenantContext.execute("T10001", () -> client().call(TARGET, PATH, Map.of("layer", "路网")));

		assertThat(data.path("count").asInt()).isEqualTo(3);
		assertThat(sent).hasSize(1);
		Sent request = sent.get(0);
		assertThat(request.uri()).isEqualTo(URI.create("http://10.0.0.5:8102/inner/gis/insight/snapshot"));
		assertThat(mapper.readTree(request.body()).path("layer").asText()).isEqualTo("路网");
		Map<String, String> headers = request.headers();
		assertThat(headers.get("Content-Type")).isEqualTo("application/json");
		assertThat(headers.get(InnerProtocol.HEADER_CALLER)).isEqualTo(SELF);
		assertThat(headers.get(InnerProtocol.HEADER_TENANT)).isEqualTo("T10001");
		assertThat(headers.get(InnerProtocol.HEADER_TIMESTAMP)).isEqualTo(Long.toString(NOW));
		assertThat(request.nonce()).matches(InnerProtocol.NONCE);
		assertThat(headers.get(InnerProtocol.HEADER_SIGNATURE)).isEqualTo(InnerProtocol.signRequest(KEY, "POST", PATH,
			Long.toString(NOW), request.nonce(), SELF, TARGET, "T10001", request.body()));
		assertThat(request.readTimeout()).isEqualTo(Duration.ofSeconds(10));
	}

	/** 不带用户的登录令牌，也不带 Cookie：对方只知道租户 */
	@Test
	void neverForwardsUserCredentials() {
		oneInstance();

		TenantContext.execute("T10001", () -> client().call(TARGET, PATH, Map.of()));

		assertThat(sent.get(0).headers().keySet()).containsExactlyInAnyOrder("Content-Type",
			InnerProtocol.HEADER_CALLER, InnerProtocol.HEADER_TENANT, InnerProtocol.HEADER_TIMESTAMP,
			InnerProtocol.HEADER_NONCE, InnerProtocol.HEADER_SIGNATURE, InnerProtocol.HEADER_WAIT);
	}

	/** 把「这边最多等多久」告诉对方，对方据此取消跑得太久的查询 */
	@Test
	void tellsTheOtherSideHowLongItWillWait() {
		oneInstance();
		InnerClient client = client();

		TenantContext.execute("000000", () -> client.call(TARGET, PATH, Map.of()));
		TenantContext.execute("000000", () -> client.callSlow(TARGET, PATH, Map.of()));

		assertThat(sent.get(0).headers().get(InnerProtocol.HEADER_WAIT)).isEqualTo("10000");
		assertThat(sent.get(1).headers().get(InnerProtocol.HEADER_WAIT)).isEqualTo("120000");
	}

	/** 在「不按租户过滤」的上下文里发起的调用，对方也不过滤 */
	@Test
	void ignoreScopeIsSentAsAllTenants() {
		oneInstance();

		TenantContext.ignore(() -> client().call(TARGET, PATH, Map.of()));
		TenantContext.execute(TenantContext.ALL, () -> client().call(TARGET, PATH, Map.of()));

		assertThat(sent).extracting(s -> s.headers().get(InnerProtocol.HEADER_TENANT)).containsExactly("-1", "-1");
	}

	/** 没有租户上下文时不能退而求其次地不带租户或者带 -1 发出去 */
	@Test
	void withoutTenantContextNothingIsSent() {
		oneInstance();

		InnerCallException e = catchThrowableOfType(InnerCallException.class, () -> client().call(TARGET, PATH, Map.of()));

		assertThat(e.kind()).isEqualTo(Kind.FAILED);
		assertThat(e).hasMessageContaining("没有租户上下文");
		assertThat(sent).isEmpty();
	}

	@Test
	void tenantThatCannotTravelInAHeaderIsRefused() {
		oneInstance();

		assertThatThrownBy(() -> TenantContext.execute("租户 A", () -> client().call(TARGET, PATH, Map.of())))
			.isInstanceOf(InnerCallException.class);
		assertThat(sent).isEmpty();
	}

	@Test
	void nullBodyIsSentAsEmptyObject() {
		oneInstance();

		TenantContext.execute("000000", () -> client().call(TARGET, PATH, null));

		assertThat(new String(sent.get(0).body(), StandardCharsets.UTF_8)).isEqualTo("{}");
	}

	@Test
	void callSlowWaitsLonger() {
		oneInstance();

		TenantContext.execute("000000", () -> client().callSlow(TARGET, PATH, Map.of()));

		assertThat(sent.get(0).readTimeout()).isEqualTo(Duration.ofSeconds(120));
	}

	@Test
	void traceIdIsForwardedOnlyWhenWellFormed() {
		oneInstance();

		MDC.put("traceId", "abc-123");
		TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of()));
		MDC.put("traceId", "bad id\r\nX-Injected: 1");
		TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of()));

		assertThat(sent.get(0).headers().get("X-Trace-Id")).isEqualTo("abc-123");
		assertThat(sent.get(1).headers()).doesNotContainKey("X-Trace-Id");
	}

	@Test
	void oversizedRequestBodyIsNotSent() {
		oneInstance();
		InnerProperties properties = properties();
		properties.setMaxRequestSize(DataSize.ofBytes(32));

		assertThatThrownBy(() -> TenantContext.execute("000000",
			() -> client(properties).call(TARGET, PATH, Map.of("text", "x".repeat(100)))))
			.isInstanceOf(InnerCallException.class)
			.hasMessageContaining("超过上限");
		assertThat(sent).isEmpty();
	}

	@Test
	void malformedServiceNameOrPathIsAProgrammingError() {
		for (String[] bad : new String[][] {{"mugsun gis", PATH}, {TARGET, "/system/user/list"}, {TARGET, "/inner"},
			{TARGET, "/inner/gis/../x"}, {TARGET, "/inner/gis?x=1"}}) {
			assertThatThrownBy(() -> TenantContext.execute("000000", () -> client().call(bad[0], bad[1], Map.of())))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(sent).isEmpty();
	}

	// ---------- 对方在不在 ----------

	@Test
	void withoutSecretNothingIsSent() {
		InnerProperties properties = new InnerProperties();
		properties.validate();
		InnerClient client = client(properties);

		InnerCallException e = catchThrowableOfType(InnerCallException.class,
			() -> TenantContext.execute("000000", () -> client.call(TARGET, PATH, Map.of())));

		assertThat(e.kind()).isEqualTo(Kind.NOT_CONFIGURED);
		assertThat(e.absent()).isTrue();
		verifyNoInteractions(discovery);
		assertThat(sent).isEmpty();
	}

	/** 对方在注册中心里、本服务没配密钥：算调不了；日志里对每个服务只提示一次，免得每个请求都刷一行 */
	@Test
	void withoutSecretAPresentServiceIsUnreachableAndHintedOnce() {
		InnerProperties properties = new InnerProperties();
		properties.validate();
		InnerClient client = client(properties);
		oneInstance();
		when(discovery.getInstances("mugsun-track")).thenReturn(List.of(instance("10.0.0.7")));
		when(discovery.getInstances("mugsun-absent")).thenReturn(List.of());

		List<String> warnings = warningsDuring(() -> {
			assertThat(client.reachable(TARGET)).isFalse();
			assertThat(client.reachable(TARGET)).isFalse();
			assertThat(client.reachable("mugsun-track")).isFalse();
			assertThat(client.reachable("mugsun-absent")).isFalse();
			assertThat(client.reachable(TARGET)).isFalse();
		});

		assertThat(warnings).hasSize(2);
		assertThat(warnings.get(0)).contains(TARGET).contains("MUGSUN_INNER_SECRET");
		assertThat(warnings.get(1)).contains("mugsun-track").contains("MUGSUN_INNER_SECRET");
		assertThat(sent).isEmpty();
	}

	/** 配了密钥时不该有这条提示 */
	@Test
	void withSecretThereIsNoHint() {
		oneInstance();
		InnerClient client = client();

		List<String> warnings = warningsDuring(() -> assertThat(client.reachable(TARGET)).isTrue());

		assertThat(warnings).isEmpty();
	}

	@Test
	void noInstanceIsReportedAsAbsent() {
		when(discovery.getInstances(TARGET)).thenReturn(List.of());

		InnerCallException e = catchThrowableOfType(InnerCallException.class,
			() -> TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of())));

		assertThat(e.kind()).isEqualTo(Kind.NO_INSTANCE);
		assertThat(e.absent()).isTrue();
		assertThat(client().reachable(TARGET)).isFalse();
	}

	@Test
	void reachableFollowsTheRegistry() {
		oneInstance();
		assertThat(client().reachable(TARGET)).isTrue();

		when(discovery.getInstances("mugsun-track")).thenThrow(new IllegalStateException("registry down"));
		assertThat(client().reachable("mugsun-track")).isFalse();
	}

	@Test
	void registryFailureIsAFailureNotAbsence() {
		when(discovery.getInstances(TARGET)).thenThrow(new IllegalStateException("registry down"));

		InnerCallException e = catchThrowableOfType(InnerCallException.class,
			() -> TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of())));

		assertThat(e.kind()).isEqualTo(Kind.FAILED);
		assertThat(e.absent()).isFalse();
	}

	// ---------- 响应 ----------

	@Test
	void businessFailureBecomesServiceExceptionWithTheSameMessage() {
		oneInstance();
		responder = request -> signedOk(request, "{\"ok\":false,\"code\":\"business\",\"msg\":\"图层模块没有启用\"}");

		assertThatThrownBy(() -> TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of())))
			.isInstanceOf(ServiceException.class)
			.hasMessage("图层模块没有启用");
	}

	@Test
	void missingOrNullDataBecomesJsonNull() {
		oneInstance();

		responder = request -> signedOk(request, "{\"ok\":true,\"data\":null}");
		JsonNode explicit = TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of()));
		responder = request -> signedOk(request, "{\"ok\":true}");
		JsonNode missing = TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of()));

		assertThat(explicit.isNull()).isTrue();
		assertThat(missing.isNull()).isTrue();
	}

	/** 快照里的数字要原样转给前端，不能在中途变成 double 丢掉精度 */
	@Test
	void decimalsKeepTheirPrecision() {
		oneInstance();
		responder = request -> signedOk(request,
			"{\"ok\":true,\"data\":{\"area\":12345678901234567890.123456789,\"ratio\":1.10,\"id\":\"1893627182736452609\"}}");

		JsonNode data = TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of()));

		assertThat(data.path("area").decimalValue()).isEqualTo(new BigDecimal("12345678901234567890.123456789"));
		assertThat(data.path("ratio").decimalValue()).isEqualTo(new BigDecimal("1.10"));
		assertThat(data.path("id").asText()).isEqualTo("1893627182736452609");
	}

	@Test
	void status401MeansRejected() {
		oneInstance();
		responder = request -> new InnerClient.Reply(401, null, "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));

		InnerCallException e = catchThrowableOfType(InnerCallException.class,
			() -> TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of())));

		assertThat(e.kind()).isEqualTo(Kind.REJECTED);
		assertThat(e.absent()).isFalse();
	}

	/** 不是 200 的响应一律算失败，哪怕里面写着成功、签名也对 */
	@Test
	void nonOkStatusIsFailureWhateverTheBodySays() {
		oneInstance();
		for (int status : new int[] {201, 302, 400, 404, 500, 503}) {
			responder = request -> {
				InnerClient.Reply ok = signedOk(request, "{\"ok\":true,\"data\":{\"count\":3}}");
				return new InnerClient.Reply(status, ok.signature(), ok.body());
			};

			InnerCallException e = catchThrowableOfType(InnerCallException.class,
				() -> TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of())));

			assertThat(e.kind()).as("HTTP " + status).isEqualTo(Kind.FAILED);
		}
	}

	/** 注册中心里混进来的假实例没有密钥，它的回话不会被采信 */
	@Test
	void replyWithoutValidSignatureIsNotTrusted() {
		oneInstance();
		byte[] body = "{\"ok\":true,\"data\":{\"count\":999}}".getBytes(StandardCharsets.UTF_8);
		List<Responder> forgeries = List.of(
			request -> new InnerClient.Reply(200, null, body),
			request -> new InnerClient.Reply(200, "0".repeat(64), body),
			// 用别的密钥签的
			request -> new InnerClient.Reply(200, InnerProtocol.signResponse(
				"another-key-0123456789abcdef-XYZ---".getBytes(StandardCharsets.UTF_8), PATH, request.nonce(), SELF,
				TARGET, body), body),
			// 拿另一次请求的响应来充数（随机串不是这一次的）
			request -> new InnerClient.Reply(200, InnerProtocol.signResponse(KEY, PATH, "some-other-request-nonce", SELF,
				TARGET, body), body),
			// 签名是对的，但响应体被改过
			request -> new InnerClient.Reply(200, InnerProtocol.signResponse(KEY, PATH, request.nonce(), SELF, TARGET,
				"{\"ok\":true,\"data\":{\"count\":3}}".getBytes(StandardCharsets.UTF_8)), body),
			// 是别的服务签的
			request -> new InnerClient.Reply(200, InnerProtocol.signResponse(KEY, PATH, request.nonce(), SELF,
				"mugsun-track", body), body),
			// 拿请求自己的签名当响应签名
			request -> new InnerClient.Reply(200, request.headers().get(InnerProtocol.HEADER_SIGNATURE), body));

		for (Responder forgery : forgeries) {
			responder = forgery;

			InnerCallException e = catchThrowableOfType(InnerCallException.class,
				() -> TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of())));

			assertThat(e.kind()).isEqualTo(Kind.FAILED);
			assertThat(e).hasMessageContaining("没有通过验签");
		}
	}

	@Test
	void replyOverTheSizeLimitIsFailure() {
		oneInstance();
		responder = request -> new InnerClient.Reply(200, "0".repeat(64), null);

		assertThatThrownBy(() -> TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of())))
			.isInstanceOf(InnerCallException.class)
			.hasMessageContaining("超过上限");
	}

	@Test
	void signedButMalformedReplyIsFailure() {
		oneInstance();
		responder = request -> signedOk(request, "not json");

		InnerCallException e = catchThrowableOfType(InnerCallException.class,
			() -> TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of())));

		assertThat(e.kind()).isEqualTo(Kind.FAILED);
	}

	// ---------- 连不上、超时 ----------

	@Test
	void connectFailureMovesOnToAnotherInstanceWithAFreshNonce() {
		twoInstances();
		responder = request -> {
			if (sent.size() == 1) {
				throw new ConnectException("Connection refused");
			}
			return signedOk(request, "{\"ok\":true,\"data\":{\"count\":3}}");
		};

		JsonNode data = TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of()));

		assertThat(data.path("count").asInt()).isEqualTo(3);
		assertThat(sent).hasSize(2);
		assertThat(sent.get(0).uri()).isNotEqualTo(sent.get(1).uri());
		assertThat(sent.get(0).nonce()).isNotEqualTo(sent.get(1).nonce());
	}

	@Test
	void givesUpAfterTwoUnreachableInstances() {
		when(discovery.getInstances(TARGET)).thenReturn(List.of(instance("10.0.0.5"), instance("10.0.0.6"),
			instance("10.0.0.7")));
		responder = request -> {
			throw new HttpConnectTimeoutException("connect timed out");
		};

		InnerCallException e = catchThrowableOfType(InnerCallException.class,
			() -> TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of())));

		assertThat(e.kind()).isEqualTo(Kind.FAILED);
		assertThat(e.absent()).isFalse();
		assertThat(sent).hasSize(2);
	}

	/** 已经连上以后的失败不重试：对方可能已经执行了，再发一遍等于让大模型再跑一次 */
	@Test
	void failureAfterConnectingIsNotRetried() {
		twoInstances();
		for (IOException failure : List.of(new HttpTimeoutException("request timed out"), new IOException("reset"))) {
			sent.clear();
			responder = request -> {
				throw failure;
			};

			InnerCallException e = catchThrowableOfType(InnerCallException.class,
				() -> TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of())));

			assertThat(e.kind()).isEqualTo(Kind.FAILED);
			assertThat(sent).hasSize(1);
		}
	}

	// ---------- 熔断 ----------

	/** 对方连得上却不回话时，前几个请求等到超时，之后的请求不再发、马上失败，用户不用每次都等 */
	@Test
	void afterRepeatedTimeoutsCallsFailAtOnceWithoutBeingSent() {
		oneInstance();
		responder = request -> {
			throw new HttpTimeoutException("request timed out");
		};
		InnerClient client = client();
		for (int i = 0; i < 3; i++) {
			assertThat(failureOf(client).kind()).isEqualTo(Kind.FAILED);
		}
		assertThat(sent).hasSize(3);

		InnerCallException e = failureOf(client);

		assertThat(e.kind()).isEqualTo(Kind.BREAKER_OPEN);
		assertThat(e.absent()).as("熔断不等于对方没有部署").isFalse();
		assertThat(sent).hasSize(3);
	}

	@Test
	void afterThePauseOneCallIsLetThroughAndAnAnswerRestoresNormalService() {
		oneInstance();
		responder = request -> {
			throw new HttpTimeoutException("request timed out");
		};
		InnerClient client = client();
		for (int i = 0; i < 3; i++) {
			failureOf(client);
		}
		ticker.addAndGet(9_999);
		assertThat(failureOf(client).kind()).isEqualTo(Kind.BREAKER_OPEN);

		ticker.addAndGet(1);
		responder = request -> signedOk(request, OK_REPLY);
		JsonNode data = TenantContext.execute("000000", () -> client.call(TARGET, PATH, Map.of()));

		assertThat(data.path("count").asInt()).isEqualTo(3);
		assertThat(sent).hasSize(4);
		TenantContext.execute("000000", () -> client.call(TARGET, PATH, Map.of()));
		assertThat(sent).hasSize(5);
	}

	/** 两个实例坏了一个：熔断以后请求只发给好的那个，不再有请求去坏的那个上面等超时 */
	@Test
	void aBrokenInstanceIsLeftAloneWhileTheHealthyOneKeepsServing() {
		twoInstances();
		responder = request -> {
			if (request.uri().getHost().equals("10.0.0.5")) {
				throw new HttpTimeoutException("request timed out");
			}
			return signedOk(request, OK_REPLY);
		};
		InnerClient client = client();
		// 每次调用随机先试一个实例，碰上坏的那个就失败一次；失败满三次它就被熔断
		int failures = 0;
		for (int i = 0; i < 500 && failures < 3; i++) {
			if (failureOf(client) != null) {
				failures++;
			}
		}
		assertThat(failures).isEqualTo(3);
		sent.clear();

		for (int i = 0; i < 50; i++) {
			TenantContext.execute("000000", () -> client.call(TARGET, PATH, Map.of()));
		}

		assertThat(sent).hasSize(50).allMatch(request -> request.uri().getHost().equals("10.0.0.6"));
	}

	/**
	 * 有的故障只跟一个租户有关（它的独立数据库连不上、它自己配的大模型不回话）。熔断按租户分开：
	 * 这个租户被熔断以后，别的租户照常调得通；别的租户调通了，也不会让出故障的租户又去等超时
	 */
	@Test
	void oneTenantsFailuresDoNotStopCallsForOtherTenants() {
		oneInstance();
		responder = request -> {
			if ("T10001".equals(request.headers().get(InnerProtocol.HEADER_TENANT))) {
				throw new HttpTimeoutException("request timed out");
			}
			return signedOk(request, OK_REPLY);
		};
		InnerClient client = client();
		for (int i = 0; i < 3; i++) {
			assertThat(failureOf(client, "T10001").kind()).isEqualTo(Kind.FAILED);
		}
		assertThat(failureOf(client, "T10001").kind()).isEqualTo(Kind.BREAKER_OPEN);
		sent.clear();

		for (int i = 0; i < 20; i++) {
			TenantContext.execute("000000", () -> client.call(TARGET, PATH, Map.of()));
		}

		assertThat(sent).hasSize(20);
		assertThat(failureOf(client, "T10001").kind()).as("别的租户调通了，出故障的租户仍然熔断着").isEqualTo(Kind.BREAKER_OPEN);
		assertThat(sent).hasSize(20);
	}

	/** 连不上也算失败：注册中心还没摘掉一个已经断电的实例时，不用每次都先在它身上耗掉连接超时 */
	@Test
	void anUnreachableInstanceIsSkippedAfterRepeatedConnectFailures() {
		twoInstances();
		responder = request -> {
			if (request.uri().getHost().equals("10.0.0.5")) {
				throw new HttpConnectTimeoutException("connect timed out");
			}
			return signedOk(request, OK_REPLY);
		};
		InnerClient client = client();
		// 连不上会换另一个实例，所以每次调用都成功；一直调到坏的那个被试过三次
		for (int i = 0; i < 500 && sentTo("10.0.0.5") < 3; i++) {
			TenantContext.execute("000000", () -> client.call(TARGET, PATH, Map.of()));
		}
		assertThat(sentTo("10.0.0.5")).isEqualTo(3);
		sent.clear();

		for (int i = 0; i < 50; i++) {
			TenantContext.execute("000000", () -> client.call(TARGET, PATH, Map.of()));
		}

		assertThat(sent).hasSize(50).allMatch(request -> request.uri().getHost().equals("10.0.0.6"));
	}

	/** 对方明确答复了的失败不计入：它在正常工作，熔断帮不上忙，还会把「密钥不一致」这样的真正原因盖住 */
	@Test
	void clearAnswersNeverOpenTheBreaker() {
		oneInstance();
		InnerClient client = client();

		responder = request -> new InnerClient.Reply(401, null, new byte[0]);
		for (int i = 0; i < 5; i++) {
			assertThat(failureOf(client).kind()).isEqualTo(Kind.REJECTED);
		}
		responder = request -> new InnerClient.Reply(404, null, "{}".getBytes(StandardCharsets.UTF_8));
		for (int i = 0; i < 5; i++) {
			assertThat(failureOf(client).kind()).isEqualTo(Kind.FAILED);
		}
		responder = request -> signedOk(request, "{\"ok\":false,\"code\":\"business\",\"msg\":\"图层模块没有启用\"}");
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> TenantContext.execute("000000", () -> client.call(TARGET, PATH, Map.of())))
				.isInstanceOf(ServiceException.class);
		}

		assertThat(sent).hasSize(15);
	}

	@Test
	void serverErrorsAndUntrustedRepliesDoCount() {
		List<InnerClient.Reply> bad = List.of(
			new InnerClient.Reply(500, null, "{}".getBytes(StandardCharsets.UTF_8)),
			new InnerClient.Reply(200, "0".repeat(64), OK_REPLY.getBytes(StandardCharsets.UTF_8)));
		for (InnerClient.Reply reply : bad) {
			sent.clear();
			oneInstance();
			responder = request -> reply;
			InnerClient client = client();
			for (int i = 0; i < 3; i++) {
				assertThat(failureOf(client).kind()).isEqualTo(Kind.FAILED);
			}

			assertThat(failureOf(client).kind()).as("HTTP " + reply.status()).isEqualTo(Kind.BREAKER_OPEN);
			assertThat(sent).hasSize(3);
		}
	}

	@Test
	void breakerCanBeTurnedOff() {
		oneInstance();
		responder = request -> {
			throw new HttpTimeoutException("request timed out");
		};
		InnerProperties properties = properties();
		properties.setBreakerFailures(0);
		InnerClient client = client(properties);

		for (int i = 0; i < 10; i++) {
			assertThat(failureOf(client).kind()).isEqualTo(Kind.FAILED);
		}
		assertThat(sent).hasSize(10);
	}

	// ---------- 对方忙 ----------

	/** 对方回「忙」表示这次没有执行，换一个实例再发不会让同一件事做两遍 */
	@Test
	void whenOneInstanceIsBusyAnotherIsTried() {
		twoInstances();
		responder = request -> signedOk(request, sent.size() == 1 ? BUSY_REPLY : OK_REPLY);

		JsonNode data = TenantContext.execute("000000", () -> client().call(TARGET, PATH, Map.of()));

		assertThat(data.path("count").asInt()).isEqualTo(3);
		assertThat(sent).hasSize(2);
		assertThat(sent.get(0).uri()).isNotEqualTo(sent.get(1).uri());
		assertThat(sent.get(0).nonce()).isNotEqualTo(sent.get(1).nonce());
	}

	@Test
	void whenEveryInstanceIsBusyTheCallFailsAsBusy() {
		when(discovery.getInstances(TARGET)).thenReturn(List.of(instance("10.0.0.5"), instance("10.0.0.6"),
			instance("10.0.0.7")));
		responder = request -> signedOk(request, BUSY_REPLY);

		InnerCallException e = failureOf(client());

		assertThat(e.kind()).isEqualTo(Kind.BUSY);
		assertThat(e.absent()).isFalse();
		assertThat(sent).hasSize(2);
	}

	/** 「忙」是对方自己在限制同时处理的个数，过一下就好：不因此熔断，下一次照常发 */
	@Test
	void busyAnswersAloneNeverOpenTheBreaker() {
		oneInstance();
		responder = request -> signedOk(request, BUSY_REPLY);
		InnerClient client = client();

		for (int i = 0; i < 10; i++) {
			assertThat(failureOf(client).kind()).isEqualTo(Kind.BUSY);
		}
		assertThat(sent).hasSize(10);
	}

	/** 没有签名的「忙」不算数，和别的没通过验签的响应一样处理 */
	@Test
	void anUnsignedBusyReplyIsNotTrusted() {
		twoInstances();
		responder = request -> new InnerClient.Reply(200, null, BUSY_REPLY.getBytes(StandardCharsets.UTF_8));

		InnerCallException e = failureOf(client());

		assertThat(e.kind()).isEqualTo(Kind.FAILED);
		assertThat(sent).hasSize(1);
	}

	// ---------- 辅助 ----------

	/** 调一次，返回它抛出的 InnerCallException；调用成功返回 null */
	private InnerCallException failureOf(InnerClient client) {
		return failureOf(client, "000000");
	}

	private InnerCallException failureOf(InnerClient client, String tenant) {
		return catchThrowableOfType(InnerCallException.class,
			() -> TenantContext.execute(tenant, () -> client.call(TARGET, PATH, Map.of())));
	}

	private long sentTo(String host) {
		return sent.stream().filter(request -> request.uri().getHost().equals(host)).count();
	}

	/** 执行期间 InnerClient 打出的 WARN 日志 */
	private static List<String> warningsDuring(Runnable action) {
		Logger logger = (Logger) LoggerFactory.getLogger(InnerClient.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
		}
		return appender.list.stream()
			.filter(event -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

	private static InnerProperties properties() {
		InnerProperties properties = new InnerProperties();
		properties.setSecret(SECRET);
		properties.validate();
		return properties;
	}

	private InnerClient client() {
		return client(properties());
	}

	private InnerClient client(InnerProperties properties) {
		InnerClient.Transport transport = (uri, headers, body, readTimeout) -> {
			Sent request = new Sent(uri, Map.copyOf(headers), body, readTimeout);
			sent.add(request);
			return responder.reply(request);
		};
		return new InnerClient(properties, discovery, mapper, SELF, transport, () -> NOW, ticker::get);
	}

	private void oneInstance() {
		when(discovery.getInstances(TARGET)).thenReturn(List.of(instance("10.0.0.5")));
	}

	private void twoInstances() {
		when(discovery.getInstances(TARGET)).thenReturn(List.of(instance("10.0.0.5"), instance("10.0.0.6")));
	}

	private static ServiceInstance instance(String host) {
		return new DefaultServiceInstance(host + ":8102", TARGET, host, 8102, false);
	}

	private static InnerClient.Reply signedOk(Sent request, String json) {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		return new InnerClient.Reply(200, InnerProtocol.signResponse(KEY, PATH, request.nonce(), SELF, TARGET, body), body);
	}
}
