package com.mugsun.cloud.common.inner;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.cloud.common.lane.LaneContext;
import com.mugsun.cloud.common.lane.Lanes;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InnerRequestFilterTest {

	/** 测试用的假密钥 */
	private static final String SECRET = "test-only-key-0123456789abcdef-XYZ";

	private static final String SELF = "mugsun-gis";

	private static final String CALLER = "mugsun-ai";

	private static final String PATH = "/inner/gis/insight/snapshot";

	private static final long NOW = 1_700_000_000_000L;

	private static final byte[] BODY = "{\"layer\":\"路网\"}".getBytes(StandardCharsets.UTF_8);

	private final ObjectMapper mapper = new ObjectMapper();

	// 下面几个容器会被几个同时在跑的请求一起写（测并发上限的那几条），所以用能并发写的
	private final Set<String> seenNonces = ConcurrentHashMap.newKeySet();

	private final List<Duration> nonceTtls = new CopyOnWriteArrayList<>();

	private final List<InnerCall> calls = new CopyOnWriteArrayList<>();

	private final List<String> tenantRawInside = new CopyOnWriteArrayList<>();

	private final List<Object[]> tenantIdsInside = new CopyOnWriteArrayList<>();

	/** 要和别的请求同时跑的请求，放到这里面跑 */
	private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

	private boolean nonceStoreBroken;

	private volatile Function<InnerCall, Object> behaviour = call -> Map.of("count", 3);

	private MockFilterChain chain;

	private int nonceCounter;

	@AfterEach
	void clearTenant() {
		TenantContext.clear();
		InnerDeadline.clear();
		LaneContext.clear();
		background.shutdownNow();
	}

	// ---------- 不归这个过滤器管的路径 ----------

	@Test
	void otherPathsGoDownTheChainUntouched() throws Exception {
		for (String path : List.of("/system/user/list", "/innerx", "/inner-api/x", "/api/inner/x", "/Inner/x")) {
			MockHttpServletResponse response = run(filter(), new MockHttpServletRequest("GET", path));

			assertThat(chain.getRequest()).as(path).isNotNull();
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getContentAsByteArray()).isEmpty();
		}
		assertThat(calls).isEmpty();
	}

	/**
	 * 带编码、双斜杠、; 参数的写法不在这里拦（后面没有 Controller 接它们），但它们也进不了内部接口：
	 * 即使签名是对的，接口也没有被调用。
	 */
	@Test
	void pathVariantsNeverReachAnEndpoint() throws Exception {
		for (String path : List.of("//inner/gis/insight/snapshot", "/inner;x=1/gis/insight/snapshot",
			"/%69nner/gis/insight/snapshot", "/./inner/gis/insight/snapshot")) {
			run(filter(), signed(path, "000000", BODY));

			assertThat(chain.getRequest()).as(path).isNotNull();
		}
		assertThat(calls).isEmpty();
	}

	@Test
	void contextPathIsStripped() throws Exception {
		MockHttpServletRequest request = signed(PATH, "000000", BODY);
		request.setContextPath("/app");
		request.setRequestURI("/app" + PATH);

		MockHttpServletResponse response = run(filter(), request);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(calls).hasSize(1);
	}

	// ---------- 正常调用 ----------

	@Test
	void validCallRunsTheEndpointInsideTheCallersTenant() throws Exception {
		MockHttpServletRequest request = signed(PATH, "T10001", BODY);

		MockHttpServletResponse response = run(filter(), request);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).as("不再往后传").isNull();
		assertThat(calls).hasSize(1);
		assertThat(calls.get(0).caller()).isEqualTo(CALLER);
		assertThat(calls.get(0).tenant()).isEqualTo("T10001");
		assertThat(calls.get(0).body().path("layer").asText()).isEqualTo("路网");
		assertThat(tenantRawInside).containsExactly("T10001");
		assertThat(tenantIdsInside.get(0)).containsExactly("T10001");
		assertThat(TenantContext.getRaw()).as("执行完要恢复").isNull();

		JsonNode json = mapper.readTree(response.getContentAsByteArray());
		assertThat(json.path("ok").asBoolean()).isTrue();
		assertThat(json.path("data").path("count").asInt()).isEqualTo(3);
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
		assertResponseSigned(request, response);
	}

	/** -1 表示调用方那边不按租户过滤（平台管理员看全部租户），这边也不加租户条件 */
	@Test
	void allTenantsMarkerMeansNoTenantCondition() throws Exception {
		MockHttpServletResponse response = run(filter(), signed(PATH, "-1", BODY));

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(tenantRawInside).containsExactly("-1");
		assertThat(tenantIdsInside.get(0)).as("null 即不加租户条件").isNull();
	}

	/** 进来之前线程上残留的租户不能影响这次调用，调用完还要恢复成原样 */
	@Test
	void tenantOnTheThreadIsOverriddenAndRestored() throws Exception {
		TenantContext.set("LEFTOVER");

		run(filter(), signed(PATH, "T10001", BODY));

		assertThat(tenantRawInside).containsExactly("T10001");
		assertThat(TenantContext.getRaw()).isEqualTo("LEFTOVER");
	}

	@Test
	void emptyOrNullBodyBecomesAnEmptyObject() throws Exception {
		for (byte[] body : List.of(new byte[0], "null".getBytes(StandardCharsets.UTF_8))) {
			calls.clear();

			MockHttpServletResponse response = run(filter(), signed(PATH, "000000", body));

			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(calls.get(0).body().isObject()).isTrue();
			assertThat(calls.get(0).body().isEmpty()).isTrue();
		}
	}

	@Test
	void nullResultIsReturnedAsJsonNull() throws Exception {
		behaviour = call -> null;
		MockHttpServletRequest request = signed(PATH, "000000", BODY);

		MockHttpServletResponse response = run(filter(), request);

		JsonNode json = mapper.readTree(response.getContentAsByteArray());
		assertThat(json.path("ok").asBoolean()).isTrue();
		assertThat(json.get("data").isNull()).isTrue();
		assertResponseSigned(request, response);
	}

	@Test
	void requestWithinTheClockSkewIsAccepted() throws Exception {
		for (long drift : new long[] {-60_000, 60_000}) {
			MockHttpServletRequest request = request(PATH, CALLER, "000000", Long.toString(NOW + drift), nonce(), BODY,
				SELF, SECRET);

			assertThat(run(filter(), request).getStatus()).as("drift " + drift).isEqualTo(200);
		}
	}

	/** 随机串要记到这个请求的时间戳不再有效为止：前后各一个时间差上限，再留一点余量 */
	@Test
	void nonceIsRememberedForTheWholeAcceptanceWindow() throws Exception {
		run(filter(), signed(PATH, "000000", BODY));

		assertThat(nonceTtls).containsExactly(Duration.ofSeconds(125));
	}

	// ---------- 业务失败和故障 ----------

	@Test
	void serviceExceptionIsReturnedAsSignedBusinessFailure() throws Exception {
		behaviour = call -> {
			throw new ServiceException("图层模块没有启用");
		};
		MockHttpServletRequest request = signed(PATH, "000000", BODY);

		MockHttpServletResponse response = run(filter(), request);

		assertThat(response.getStatus()).isEqualTo(200);
		JsonNode json = mapper.readTree(response.getContentAsByteArray());
		assertThat(json.path("ok").asBoolean()).isFalse();
		assertThat(json.path("code").asText()).isEqualTo("business");
		assertThat(json.path("msg").asText()).isEqualTo("图层模块没有启用");
		assertResponseSigned(request, response);
		assertThat(TenantContext.getRaw()).isNull();
	}

	/** 故障的细节只记在这边的日志里，不带给调用方；这种响应不签名，调用方不会采信 */
	@Test
	void unexpectedExceptionBecomesUnsigned500WithoutDetails() throws Exception {
		behaviour = call -> {
			throw new IllegalStateException("jdbc:postgresql://db.internal:5432/mugsun 连接失败");
		};

		MockHttpServletResponse response = run(filter(), signed(PATH, "000000", BODY));

		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(response.getHeader(InnerProtocol.HEADER_SIGNATURE)).isNull();
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("jdbc").doesNotContain("db.internal");
		assertThat(TenantContext.getRaw()).isNull();
	}

	@Test
	void resultThatCannotBeSerializedBecomes500() throws Exception {
		behaviour = call -> new Object() {
			@SuppressWarnings("unused")
			public String getBroken() {
				throw new IllegalStateException("boom");
			}
		};

		MockHttpServletResponse response = run(filter(), signed(PATH, "000000", BODY));

		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(response.getHeader(InnerProtocol.HEADER_SIGNATURE)).isNull();
	}

	@Test
	void malformedJsonFromAnAuthenticatedCallerIs400() throws Exception {
		MockHttpServletResponse response = run(filter(), signed(PATH, "000000", "{not json".getBytes(StandardCharsets.UTF_8)));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(calls).isEmpty();
	}

	@Test
	void unknownEndpointIs404OnlyForAuthenticatedCallers() throws Exception {
		MockHttpServletResponse authenticated = run(filter(), signed("/inner/gis/nothing-here", "000000", BODY));
		assertThat(authenticated.getStatus()).isEqualTo(404);

		// 没有密钥的人探测不出哪些路径存在：存在和不存在的路径得到的响应一样
		MockHttpServletResponse unknownPath = run(filter(),
			request("/inner/gis/nothing-here", CALLER, "000000", Long.toString(NOW), nonce(), BODY, SELF, wrongSecret()));
		MockHttpServletResponse knownPath = run(filter(),
			request(PATH, CALLER, "000000", Long.toString(NOW), nonce(), BODY, SELF, wrongSecret()));
		assertThat(unknownPath.getStatus()).isEqualTo(401);
		assertThat(knownPath.getStatus()).isEqualTo(401);
		assertThat(unknownPath.getContentAsByteArray()).isEqualTo(knownPath.getContentAsByteArray());
		assertThat(calls).isEmpty();
	}

	// ---------- 拒绝 ----------

	@Test
	void withoutSecretEverythingUnderInnerIsRefused() throws Exception {
		InnerProperties properties = new InnerProperties();
		properties.validate();
		InnerRequestFilter filter = filter(properties);

		MockHttpServletResponse response = run(filter, signed(PATH, "000000", BODY));

		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(chain.getRequest()).isNull();
		assertThat(calls).isEmpty();
		assertThat(seenNonces).isEmpty();
	}

	@Test
	void onlyPostJsonWithoutQueryStringIsAccepted() throws Exception {
		MockHttpServletRequest get = signed(PATH, "000000", BODY);
		get.setMethod("GET");
		MockHttpServletResponse getResponse = run(filter(), get);
		assertThat(getResponse.getStatus()).isEqualTo(405);
		assertThat(getResponse.getHeader("Allow")).isEqualTo("POST");

		MockHttpServletRequest query = signed(PATH, "000000", BODY);
		query.setQueryString("tenant=000000");
		assertThat(run(filter(), query).getStatus()).isEqualTo(400);

		MockHttpServletRequest form = signed(PATH, "000000", BODY);
		form.setContentType("application/x-www-form-urlencoded");
		assertThat(run(filter(), form).getStatus()).isEqualTo(415);

		MockHttpServletRequest noType = signed(PATH, "000000", BODY);
		noType.setContentType(null);
		assertThat(run(filter(), noType).getStatus()).isEqualTo(415);

		assertThat(calls).isEmpty();
		assertThat(seenNonces).isEmpty();
	}

	@Test
	void missingHeaderIsDenied() throws Exception {
		for (String header : List.of(InnerProtocol.HEADER_CALLER, InnerProtocol.HEADER_TENANT,
			InnerProtocol.HEADER_TIMESTAMP, InnerProtocol.HEADER_NONCE, InnerProtocol.HEADER_SIGNATURE)) {
			MockHttpServletRequest request = signed(PATH, "000000", BODY);
			request.removeHeader(header);

			assertThat(run(filter(), request).getStatus()).as(header).isEqualTo(401);
		}
		assertThat(calls).isEmpty();
		assertThat(seenNonces).isEmpty();
	}

	/** 格式不对的值在算签名之前就拒绝：即使对方用这些值算出了「对的」签名也不行 */
	@Test
	void malformedHeaderValuesAreDeniedEvenWhenSignedOverThem() throws Exception {
		List<MockHttpServletRequest> requests = List.of(
			request(PATH, "mugsun ai", "000000", Long.toString(NOW), nonce(), BODY, SELF, SECRET),
			request(PATH, "x".repeat(65), "000000", Long.toString(NOW), nonce(), BODY, SELF, SECRET),
			request(PATH, CALLER, "00 00", Long.toString(NOW), nonce(), BODY, SELF, SECRET),
			request(PATH, CALLER, "租户", Long.toString(NOW), nonce(), BODY, SELF, SECRET),
			request(PATH, CALLER, "000000", "17e11", nonce(), BODY, SELF, SECRET),
			request(PATH, CALLER, "000000", "-" + NOW, nonce(), BODY, SELF, SECRET),
			request(PATH, CALLER, "000000", "9".repeat(16), nonce(), BODY, SELF, SECRET),
			request(PATH, CALLER, "000000", Long.toString(NOW), "short", BODY, SELF, SECRET),
			request(PATH, CALLER, "000000", Long.toString(NOW), "has space in the nonce", BODY, SELF, SECRET));

		for (MockHttpServletRequest request : requests) {
			assertThat(run(filter(), request).getStatus()).isEqualTo(401);
		}
		assertThat(calls).isEmpty();
		assertThat(seenNonces).isEmpty();
	}

	@Test
	void signatureMustBeLowercaseHexOfTheRightLength() throws Exception {
		MockHttpServletRequest upper = signed(PATH, "000000", BODY);
		String good = upper.getHeader(InnerProtocol.HEADER_SIGNATURE);
		upper.removeHeader(InnerProtocol.HEADER_SIGNATURE);
		upper.addHeader(InnerProtocol.HEADER_SIGNATURE, good.toUpperCase());
		assertThat(run(filter(), upper).getStatus()).isEqualTo(401);

		MockHttpServletRequest truncated = signed(PATH, "000000", BODY);
		String full = truncated.getHeader(InnerProtocol.HEADER_SIGNATURE);
		truncated.removeHeader(InnerProtocol.HEADER_SIGNATURE);
		truncated.addHeader(InnerProtocol.HEADER_SIGNATURE, full.substring(0, 32));
		assertThat(run(filter(), truncated).getStatus()).isEqualTo(401);

		assertThat(calls).isEmpty();
	}

	@Test
	void staleOrFutureTimestampIsDenied() throws Exception {
		for (long drift : new long[] {-60_001, 60_001, -3_600_000, 3_600_000}) {
			MockHttpServletRequest request = request(PATH, CALLER, "000000", Long.toString(NOW + drift), nonce(), BODY,
				SELF, SECRET);

			assertThat(run(filter(), request).getStatus()).as("drift " + drift).isEqualTo(401);
		}
		assertThat(calls).isEmpty();
		assertThat(seenNonces).as("过期的请求不占 Redis").isEmpty();
	}

	@Test
	void wrongSecretIsDeniedAndLeavesNoTraceInTheNonceStore() throws Exception {
		MockHttpServletRequest request = request(PATH, CALLER, "000000", Long.toString(NOW), nonce(), BODY, SELF,
			wrongSecret());

		MockHttpServletResponse response = run(filter(), request);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader(InnerProtocol.HEADER_SIGNATURE)).isNull();
		assertThat(calls).isEmpty();
		assertThat(seenNonces).as("没有密钥的人不能往 Redis 里灌数据").isEmpty();
	}

	/** 签过名以后再改任何一项，都不认 */
	@Test
	void tamperingWithAnySignedPartIsDenied() throws Exception {
		MockHttpServletRequest body = signed(PATH, "000000", BODY);
		body.setContent("{\"layer\":\"水系\"}".getBytes(StandardCharsets.UTF_8));

		MockHttpServletRequest tenant = signed(PATH, "000000", BODY);
		tenant.removeHeader(InnerProtocol.HEADER_TENANT);
		tenant.addHeader(InnerProtocol.HEADER_TENANT, "-1");

		MockHttpServletRequest caller = signed(PATH, "000000", BODY);
		caller.removeHeader(InnerProtocol.HEADER_CALLER);
		caller.addHeader(InnerProtocol.HEADER_CALLER, "mugsun-system");

		MockHttpServletRequest timestamp = signed(PATH, "000000", BODY);
		timestamp.removeHeader(InnerProtocol.HEADER_TIMESTAMP);
		timestamp.addHeader(InnerProtocol.HEADER_TIMESTAMP, Long.toString(NOW + 1));

		MockHttpServletRequest nonce = signed(PATH, "000000", BODY);
		nonce.removeHeader(InnerProtocol.HEADER_NONCE);
		nonce.addHeader(InnerProtocol.HEADER_NONCE, "ZZZZZZZZZZZZZZZZZZZZZZ");

		MockHttpServletRequest path = signed(PATH, "000000", BODY);
		path.setRequestURI("/inner/gis/other");

		for (MockHttpServletRequest request : List.of(body, tenant, caller, timestamp, nonce, path)) {
			assertThat(run(filter(endpoint(PATH), endpoint("/inner/gis/other")), request).getStatus()).isEqualTo(401);
		}
		assertThat(calls).isEmpty();
		assertThat(seenNonces).isEmpty();
	}

	/** 发给别的服务的请求（签名里的目标是别人），原样转到这里不认 */
	@Test
	void requestSignedForAnotherServiceIsDenied() throws Exception {
		MockHttpServletRequest request = request(PATH, CALLER, "000000", Long.toString(NOW), nonce(), BODY,
			"mugsun-track", SECRET);

		assertThat(run(filter(), request).getStatus()).isEqualTo(401);
		assertThat(calls).isEmpty();
	}

	@Test
	void replayedRequestIsDenied() throws Exception {
		String nonce = nonce();
		InnerRequestFilter filter = filter();

		MockHttpServletResponse first = run(filter,
			request(PATH, CALLER, "000000", Long.toString(NOW), nonce, BODY, SELF, SECRET));
		MockHttpServletResponse second = run(filter,
			request(PATH, CALLER, "000000", Long.toString(NOW), nonce, BODY, SELF, SECRET));

		assertThat(first.getStatus()).isEqualTo(200);
		assertThat(second.getStatus()).isEqualTo(401);
		assertThat(calls).hasSize(1);
	}

	/** Redis 用不了就没法防重放，这时宁可拒绝 */
	@Test
	void brokenNonceStoreMeansRefusal() throws Exception {
		nonceStoreBroken = true;

		MockHttpServletResponse response = run(filter(), signed(PATH, "000000", BODY));

		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("redis.internal");
		assertThat(calls).isEmpty();
	}

	@Test
	void oversizedBodyIsRefusedByDeclaredLength() throws Exception {
		InnerProperties properties = properties();
		properties.setMaxRequestSize(DataSize.ofBytes(64));
		byte[] big = ("{\"x\":\"" + "a".repeat(100) + "\"}").getBytes(StandardCharsets.UTF_8);

		MockHttpServletResponse response = run(filter(properties), signed(PATH, "000000", big));

		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(response.getHeader("Connection")).isEqualTo("close");
		assertThat(calls).isEmpty();
	}

	/** 没有声明长度（分块传输）时边读边限 */
	@Test
	void oversizedBodyIsRefusedWhileReadingWhenLengthIsNotDeclared() throws Exception {
		InnerProperties properties = properties();
		properties.setMaxRequestSize(DataSize.ofBytes(64));
		byte[] big = ("{\"x\":\"" + "a".repeat(100) + "\"}").getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH) {
			@Override
			public long getContentLengthLong() {
				return -1;
			}
		};
		fill(request, CALLER, "000000", Long.toString(NOW), nonce(), big, SELF, SECRET);

		MockHttpServletResponse response = run(filter(properties), request);

		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(calls).isEmpty();
	}

	@Test
	void bodyExactlyAtTheLimitIsAccepted() throws Exception {
		InnerProperties properties = properties();
		properties.setMaxRequestSize(DataSize.ofBytes(64));
		byte[] exact = ("{\"x\":\"" + "a".repeat(64 - 8) + "\"}").getBytes(StandardCharsets.UTF_8);
		assertThat(exact).hasSize(64);

		assertThat(run(filter(properties), signed(PATH, "000000", exact)).getStatus()).isEqualTo(200);
	}

	/** 所有「身份没通过」的情况对外都是同一个响应，不透露错在哪一步 */
	@Test
	void allDenialsLookTheSame() throws Exception {
		MockHttpServletRequest missing = signed(PATH, "000000", BODY);
		missing.removeHeader(InnerProtocol.HEADER_SIGNATURE);
		MockHttpServletRequest stale = request(PATH, CALLER, "000000", Long.toString(NOW - 600_000), nonce(), BODY, SELF,
			SECRET);
		MockHttpServletRequest forged = request(PATH, CALLER, "000000", Long.toString(NOW), nonce(), BODY, SELF,
			wrongSecret());
		String usedNonce = nonce();
		InnerRequestFilter filter = filter();
		run(filter, request(PATH, CALLER, "000000", Long.toString(NOW), usedNonce, BODY, SELF, SECRET));
		MockHttpServletRequest replay = request(PATH, CALLER, "000000", Long.toString(NOW), usedNonce, BODY, SELF, SECRET);

		Set<String> bodies = new HashSet<>();
		for (MockHttpServletRequest request : List.of(missing, stale, forged, replay)) {
			MockHttpServletResponse response = run(filter, request);
			assertThat(response.getStatus()).isEqualTo(401);
			bodies.add(response.getContentAsString(StandardCharsets.UTF_8));
		}
		assertThat(bodies).hasSize(1);
	}

	// ---------- 同时处理的个数 ----------

	/** 名额占满以后新来的请求得到带签名的「忙」，接口没有执行；占着名额的请求做完，名额就还回来 */
	@Test
	void whenAllSlotsAreTakenTheNextCallIsToldBusyWithoutRunning() throws Exception {
		InnerRequestFilter filter = filter(limited(2, Duration.ofMillis(50)));
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = holdCallsInside(entered);
		Future<MockHttpServletResponse> first = inBackground(filter, signed(PATH, "000000", BODY));
		Future<MockHttpServletResponse> second = inBackground(filter, signed(PATH, "000000", BODY));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		MockHttpServletRequest third = signed(PATH, "000000", BODY);
		MockHttpServletResponse refused = run(filter, third);

		assertThat(refused.getStatus()).isEqualTo(200);
		assertThat(okOf(refused)).isFalse();
		assertThat(codeOf(refused)).isEqualTo("busy");
		assertResponseSigned(third, refused);
		assertThat(calls).as("第三个请求没有执行").hasSize(2);

		release.countDown();
		assertThat(okOf(first.get(5, TimeUnit.SECONDS))).isTrue();
		assertThat(okOf(second.get(5, TimeUnit.SECONDS))).isTrue();
		behaviour = call -> Map.of("count", 3);
		assertThat(okOf(run(filter, signed(PATH, "000000", BODY)))).as("名额还回来了").isTrue();
	}

	/** 满了的时候先等一会儿：等的这段时间里有名额空出来就接着做，不回「忙」 */
	@Test
	void aWaitingCallTakesTheSlotAsSoonAsOneIsFreed() throws Exception {
		InnerRequestFilter filter = filter(limited(1, Duration.ofSeconds(10)));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = holdCallsInside(entered);
		Future<MockHttpServletResponse> first = inBackground(filter, signed(PATH, "000000", BODY));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		Future<MockHttpServletResponse> waiting = inBackground(filter, signed(PATH, "000000", BODY));
		Thread.sleep(100);
		assertThat(waiting.isDone()).as("第二个请求在排队，既没有被拒绝").isFalse();
		assertThat(calls).as("也没有进到接口里").hasSize(1);

		release.countDown();

		assertThat(okOf(first.get(5, TimeUnit.SECONDS))).isTrue();
		assertThat(okOf(waiting.get(5, TimeUnit.SECONDS))).isTrue();
		assertThat(calls).hasSize(2);
	}

	@Test
	void theSlotIsGivenBackWhenTheEndpointFails() throws Exception {
		InnerRequestFilter filter = filter(limited(1, Duration.ZERO));

		behaviour = call -> {
			throw new IllegalStateException("boom");
		};
		assertThat(run(filter, signed(PATH, "000000", BODY)).getStatus()).isEqualTo(500);
		behaviour = call -> {
			throw new ServiceException("图层模块没有启用");
		};
		assertThat(codeOf(run(filter, signed(PATH, "000000", BODY)))).isEqualTo("business");
		behaviour = call -> Map.of("count", 3);

		assertThat(okOf(run(filter, signed(PATH, "000000", BODY)))).isTrue();
	}

	/** 按接口分开限：一个接口卡住、名额占满，别的接口照常用 */
	@Test
	void oneFullEndpointDoesNotHoldUpAnother() throws Exception {
		String otherPath = "/inner/gis/layer/count";
		InnerRequestFilter filter = new InnerRequestFilter(limited(1, Duration.ZERO), nonceStore(), mapper, SELF,
			() -> List.of(endpoint(PATH), endpoint(otherPath)), () -> NOW);
		filter.afterSingletonsInstantiated();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		behaviour = call -> {
			if (call.body().path("hold").asBoolean()) {
				entered.countDown();
				await(release);
			}
			return Map.of("count", 3);
		};
		Future<MockHttpServletResponse> held = inBackground(filter,
			signed(PATH, "000000", "{\"hold\":true}".getBytes(StandardCharsets.UTF_8)));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(codeOf(run(filter, signed(PATH, "000000", BODY)))).isEqualTo("busy");
		assertThat(okOf(run(filter, signed(otherPath, "000000", BODY)))).as("另一个接口").isTrue();

		release.countDown();
		assertThat(okOf(held.get(5, TimeUnit.SECONDS))).isTrue();
	}

	/** 名额按租户分：一个租户占到自己的份额以后，它再来的请求得到「忙」，别的租户照常进得来。按的是签过名的租户号 */
	@Test
	void oneTenantCannotFillAnEndpointForTheOthers() throws Exception {
		InnerProperties properties = limited(3, Duration.ZERO);
		properties.setMaxConcurrentPerTenant(2);
		InnerRequestFilter filter = filter(properties);
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		behaviour = call -> {
			if ("000000".equals(call.tenant())) {
				entered.countDown();
				await(release);
			}
			return Map.of("count", 3);
		};
		Future<MockHttpServletResponse> first = inBackground(filter, signed(PATH, "000000", BODY));
		Future<MockHttpServletResponse> second = inBackground(filter, signed(PATH, "000000", BODY));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		MockHttpServletRequest third = signed(PATH, "000000", BODY);
		MockHttpServletResponse refused = run(filter, third);
		MockHttpServletResponse other = run(filter, signed(PATH, "T10001", BODY));

		assertThat(codeOf(refused)).as("同一个租户的第三个").isEqualTo("busy");
		assertResponseSigned(third, refused);
		assertThat(okOf(other)).as("别的租户").isTrue();
		assertThat(calls).extracting(InnerCall::tenant).containsExactly("000000", "000000", "T10001");

		release.countDown();
		assertThat(okOf(first.get(5, TimeUnit.SECONDS))).isTrue();
		assertThat(okOf(second.get(5, TimeUnit.SECONDS))).isTrue();
		behaviour = call -> Map.of("count", 3);
		assertThat(okOf(run(filter, signed(PATH, "000000", BODY)))).as("份额还回来了").isTrue();
	}

	/** 什么都不配时：一个接口 6 个名额，同一个租户最多 5 个，剩下 1 个别的租户用；6 个都占着时谁也进不来 */
	@Test
	void byDefaultOneTenantGetsFiveOfTheSixSlots() throws Exception {
		InnerProperties properties = properties();
		properties.setMaxWait(Duration.ZERO);
		InnerRequestFilter filter = filter(properties);
		CountDownLatch entered = new CountDownLatch(5);
		CountDownLatch release = holdCallsInside(entered);
		List<Future<MockHttpServletResponse>> held = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			held.add(inBackground(filter, signed(PATH, "000000", BODY)));
		}
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(codeOf(run(filter, signed(PATH, "000000", BODY)))).as("同一个租户的第六个").isEqualTo("busy");
		held.add(inBackground(filter, signed(PATH, "T10001", BODY)));
		for (int i = 0; i < 5_000 && calls.size() < 6; i++) {
			Thread.sleep(1);
		}
		assertThat(calls).as("别的租户进得来").hasSize(6);
		assertThat(codeOf(run(filter, signed(PATH, "T10002", BODY)))).as("六个都占着，第三个租户").isEqualTo("busy");

		release.countDown();
		for (Future<MockHttpServletResponse> response : held) {
			assertThat(okOf(response.get(5, TimeUnit.SECONDS))).isTrue();
		}
	}

	/** 启动时把两个上限都写进日志：排查「为什么回了忙」时先看这一行 */
	@Test
	void bothLimitsAreLoggedAtStartup() {
		Logger logger = (Logger) LoggerFactory.getLogger(InnerRequestFilter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			filter();
		} finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
			.containsExactly("内部接口 1 个：[" + PATH + "]，每个同时最多处理 6 个请求，其中同一个租户最多 5 个");
	}

	/** 验签通过的请求才来占名额、排队：没有密钥的人既占不满名额，也探测不出接口忙不忙 */
	@Test
	void callsThatFailTheSignatureCheckNeverQueueForASlot() throws Exception {
		InnerRequestFilter filter = filter(limited(1, Duration.ofSeconds(10)));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = holdCallsInside(entered);
		Future<MockHttpServletResponse> held = inBackground(filter, signed(PATH, "000000", BODY));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		MockHttpServletResponse forged = run(filter,
			request(PATH, CALLER, "000000", Long.toString(NOW), nonce(), BODY, SELF, wrongSecret()));

		assertThat(forged.getStatus()).isEqualTo(401);
		release.countDown();
		assertThat(okOf(held.get(5, TimeUnit.SECONDS))).isTrue();
	}

	/** 忙的时候不能每个被拒绝的请求刷一行日志：一段时间里只留一行 */
	@Test
	void busyAnswersAreLoggedOnceInAWhileNotOncePerCall() throws Exception {
		InnerRequestFilter filter = filter(limited(1, Duration.ZERO));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = holdCallsInside(entered);
		Future<MockHttpServletResponse> held = inBackground(filter, signed(PATH, "000000", BODY));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		Logger logger = (Logger) LoggerFactory.getLogger(InnerGate.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			for (int i = 0; i < 5; i++) {
				assertThat(codeOf(run(filter, signed(PATH, "000000", BODY)))).isEqualTo("busy");
			}
		} finally {
			logger.detachAppender(appender);
		}

		List<String> warnings = appender.list.stream()
			.filter(event -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
		assertThat(warnings).hasSize(1);
		assertThat(warnings.get(0)).contains(PATH, "忙", "max-concurrent");
		release.countDown();
		held.get(5, TimeUnit.SECONDS);
	}

	// ---------- 调用方等多久 ----------

	/** 调用方说了最多等多久：接口执行期间查得到还剩多少时间（给查询设时限用），执行完就清掉 */
	@Test
	void theCallersWaitIsKnownWhileTheEndpointRunsAndForgottenAfterwards() throws Exception {
		List<Integer> secondsLeft = new ArrayList<>();
		behaviour = call -> {
			secondsLeft.add(InnerDeadline.secondsLeft());
			return Map.of();
		};
		MockHttpServletRequest request = signed(PATH, "000000", BODY);
		request.addHeader(InnerProtocol.HEADER_WAIT, "7000");

		MockHttpServletResponse response = run(filter(), request);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(secondsLeft).hasSize(1);
		assertThat(secondsLeft.get(0)).isBetween(6, 7);
		assertThat(InnerDeadline.active()).isFalse();
	}

	/** 没带这个头（旧版本的调用方）或者写得不对：调用照常执行，只是没有时限 */
	@Test
	void withoutAUsableWaitHeaderTheCallRunsWithoutADeadline() throws Exception {
		for (String value : new String[] {null, "", "0", "abc", "-5", "7.5", "7000ms", "99999999999"}) {
			List<Boolean> active = new ArrayList<>();
			behaviour = call -> {
				active.add(InnerDeadline.active());
				return Map.of();
			};
			MockHttpServletRequest request = signed(PATH, "000000", BODY);
			if (value != null) {
				request.addHeader(InnerProtocol.HEADER_WAIT, value);
			}

			MockHttpServletResponse response = run(filter(), request);

			assertThat(response.getStatus()).as(String.valueOf(value)).isEqualTo(200);
			assertThat(active).as(String.valueOf(value)).containsExactly(false);
		}
	}

	/** 这个头不在签名里，所以不管它写多少，都限制在 1 秒到 long-read-timeout（默认 120 秒）之间 */
	@Test
	void theCallersWaitIsKeptWithinBounds() throws Exception {
		List<Integer> secondsLeft = new ArrayList<>();
		List<Boolean> passedAfterAWhile = new ArrayList<>();
		behaviour = call -> {
			secondsLeft.add(InnerDeadline.secondsLeft());
			pause(50);
			passedAfterAWhile.add(InnerDeadline.passed());
			return Map.of();
		};
		for (String value : List.of("5", "999999999")) {
			MockHttpServletRequest request = signed(PATH, "000000", BODY);
			request.addHeader(InnerProtocol.HEADER_WAIT, value);
			run(filter(), request);
		}

		assertThat(secondsLeft).hasSize(2);
		assertThat(secondsLeft.get(0)).isEqualTo(1);
		assertThat(passedAfterAWhile.get(0)).as("写 5 毫秒也按 1 秒算：过了 50 毫秒还没到点").isFalse();
		assertThat(secondsLeft.get(1)).isBetween(119, 120);
	}

	@Test
	void theDeadlineIsForgottenEvenWhenTheEndpointFails() throws Exception {
		behaviour = call -> {
			throw new IllegalStateException("boom");
		};
		MockHttpServletRequest request = signed(PATH, "000000", BODY);
		request.addHeader(InnerProtocol.HEADER_WAIT, "7000");

		MockHttpServletResponse response = run(filter(), request);

		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(InnerDeadline.active()).isFalse();
	}

	/** 调用方只等 1 秒的话，排队也最多排 1 秒，不按 max-wait 排满：再排下去，轮到了也没人要结果 */
	@Test
	void queueingNeverOutlastsTheCaller() throws Exception {
		InnerRequestFilter filter = filter(limited(1, Duration.ofSeconds(60)));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = holdCallsInside(entered);
		Future<MockHttpServletResponse> held = inBackground(filter, signed(PATH, "000000", BODY));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		MockHttpServletRequest request = signed(PATH, "000000", BODY);
		request.addHeader(InnerProtocol.HEADER_WAIT, "1000");

		long started = System.nanoTime();
		MockHttpServletResponse response = run(filter, request);
		long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

		assertThat(codeOf(response)).isEqualTo("busy");
		assertThat(elapsedMillis).isBetween(900L, 30_000L);
		release.countDown();
		held.get(5, TimeUnit.SECONDS);
	}

	// ---------- 灰度标记 ----------

	/** 调用方带来的灰度标记在接口执行期间是当前请求的标记：接口里再去调别的服务，按它找实例。执行完清掉 */
	@Test
	void callersLaneIsCurrentWhileTheEndpointRunsAndClearedAfterwards() throws Exception {
		List<String> lanesInside = new ArrayList<>();
		behaviour = call -> {
			lanesInside.add(String.valueOf(LaneContext.current()));
			return Map.of();
		};
		MockHttpServletRequest request = signed(PATH, "000000", BODY);
		request.addHeader(Lanes.HEADER, "canary");

		MockHttpServletResponse response = run(filter(), request);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(lanesInside).containsExactly("canary");
		assertThat(LaneContext.current()).isNull();
	}

	/** 没带标记、带的写法不对、带的是 base：都按没有标记执行。线程上留着的上一个请求的标记也不沿用 */
	@Test
	void missingOrUnusableLaneMeansNoLane() throws Exception {
		List<String> lanesInside = new ArrayList<>();
		behaviour = call -> {
			lanesInside.add(String.valueOf(LaneContext.current()));
			return Map.of();
		};
		InnerRequestFilter filter = filter();
		List<String> values = new ArrayList<>(List.of("", "base", "Canary", "can ary", "abcdefghijklmnopqrstuvwxyz0123456"));
		values.add(null);

		for (String value : values) {
			MockHttpServletRequest request = signed(PATH, "000000", BODY);
			if (value != null) {
				request.addHeader(Lanes.HEADER, value);
			}
			LaneContext.set("stale");

			assertThat(run(filter, request).getStatus()).isEqualTo(200);
			assertThat(LaneContext.current()).as("执行完要清掉").isNull();
		}

		assertThat(lanesInside).hasSize(values.size()).containsOnly("null");
	}

	@Test
	void laneIsClearedWhenTheEndpointFails() throws Exception {
		behaviour = call -> {
			throw new IllegalStateException("boom");
		};
		MockHttpServletRequest request = signed(PATH, "000000", BODY);
		request.addHeader(Lanes.HEADER, "canary");

		MockHttpServletResponse response = run(filter(), request);

		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(LaneContext.current()).isNull();
	}

	/** 灰度标记不在签名里，所以只有验签通过的请求带的才算数：没通过的，标记不读，也不会留在线程上 */
	@Test
	void laneOfACallThatFailsTheSignatureCheckIsNeverRead() throws Exception {
		MockHttpServletRequest request = request(PATH, CALLER, "000000", Long.toString(NOW), nonce(), BODY, SELF,
			wrongSecret());
		request.addHeader(Lanes.HEADER, "canary");

		MockHttpServletResponse response = run(filter(), request);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(calls).isEmpty();
		assertThat(LaneContext.current()).isNull();
	}

	// ---------- 启动时的检查 ----------

	@Test
	void endpointPathMustFollowTheRule() {
		for (String bad : new String[] {null, "", "/inner", "/system/user", "/inner/Gis", "/inner/gis/", "inner/gis"}) {
			assertThatThrownBy(() -> InnerRequestFilter.index(List.of(endpoint(bad))))
				.as(String.valueOf(bad))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("路径不合规");
		}
	}

	@Test
	void duplicateEndpointPathRefusesToStart() {
		InnerRequestFilter filter = filter(endpoint(PATH), endpoint(PATH));

		assertThatThrownBy(filter::afterSingletonsInstantiated)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("路径重复");
	}

	@Test
	void serviceNameMustBeUsableInAHeader() {
		assertThatThrownBy(() -> new InnerRequestFilter(properties(), nonceStore(), mapper, "我的服务", List::of, () -> NOW))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("spring.application.name");
	}

	@Test
	void underInnerRecognisesOnlyTheLiteralPrefix() {
		assertThat(List.of("/inner", "/inner/", "/inner/gis", "/inner/gis/insight/snapshot"))
			.allMatch(InnerRequestFilter::underInner);
		assertThat(List.of("", "/", "/innerx", "/inner-x", "/api/inner/x", "//inner/x", "/inner;a=b/x", "/Inner/x",
			"/%69nner/x"))
			.noneMatch(InnerRequestFilter::underInner);
	}

	// ---------- 辅助 ----------

	private static String wrongSecret() {
		return "another-key-0123456789abcdef-XYZ---";
	}

	private static InnerProperties properties() {
		InnerProperties properties = new InnerProperties();
		properties.setSecret(SECRET);
		properties.validate();
		return properties;
	}

	private InnerNonceStore nonceStore() {
		return new InnerNonceStore(null, SELF) {
			@Override
			public boolean firstUse(String nonce, Duration ttl) {
				if (nonceStoreBroken) {
					throw new IllegalStateException("Unable to connect to redis.internal:6379");
				}
				nonceTtls.add(ttl);
				return seenNonces.add(nonce);
			}
		};
	}

	private InnerEndpoint endpoint(String path) {
		return new InnerEndpoint() {
			@Override
			public String path() {
				return path;
			}

			@Override
			public Object handle(InnerCall call) {
				calls.add(call);
				tenantRawInside.add(TenantContext.getRaw());
				tenantIdsInside.add(TenantContext.resolveTenantIds());
				return behaviour.apply(call);
			}
		};
	}

	/** 一个接口这么多个名额，不分租户（同一个租户也能占满）。要测按租户分的，拿到以后自己再改份额 */
	private static InnerProperties limited(int maxConcurrent, Duration maxWait) {
		InnerProperties properties = properties();
		properties.setMaxConcurrent(maxConcurrent);
		properties.setMaxConcurrentPerTenant(maxConcurrent);
		properties.setMaxWait(maxWait);
		return properties;
	}

	/** 让接下来的调用进到接口里就停住，直到返回的闩被放开；每进去一个，entered 减一 */
	private CountDownLatch holdCallsInside(CountDownLatch entered) {
		CountDownLatch release = new CountDownLatch(1);
		behaviour = call -> {
			entered.countDown();
			await(release);
			return Map.of("count", 3);
		};
		return release;
	}

	private static void pause(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("测试一直没有放行");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/** 在另一个线程里跑一个请求，不等它结束 */
	private Future<MockHttpServletResponse> inBackground(InnerRequestFilter filter, MockHttpServletRequest request) {
		return background.submit(() -> {
			MockHttpServletResponse response = new MockHttpServletResponse();
			filter.doFilter(request, response, new MockFilterChain());
			return response;
		});
	}

	private boolean okOf(MockHttpServletResponse response) throws Exception {
		return mapper.readTree(response.getContentAsByteArray()).path("ok").asBoolean();
	}

	private String codeOf(MockHttpServletResponse response) throws Exception {
		return mapper.readTree(response.getContentAsByteArray()).path("code").asText();
	}

	private InnerRequestFilter filter() {
		return filter(properties());
	}

	private InnerRequestFilter filter(InnerProperties properties) {
		InnerRequestFilter filter = new InnerRequestFilter(properties, nonceStore(), mapper, SELF,
			() -> List.of(endpoint(PATH)), () -> NOW);
		filter.afterSingletonsInstantiated();
		return filter;
	}

	private InnerRequestFilter filter(InnerEndpoint first, InnerEndpoint second) {
		InnerRequestFilter filter = new InnerRequestFilter(properties(), nonceStore(), mapper, SELF,
			() -> List.of(first, second), () -> NOW);
		if (!first.path().equals(second.path())) {
			filter.afterSingletonsInstantiated();
		}
		return filter;
	}

	private String nonce() {
		return "nonce-for-test-" + String.format("%07d", ++nonceCounter);
	}

	private MockHttpServletRequest signed(String path, String tenant, byte[] body) {
		return request(path, CALLER, tenant, Long.toString(NOW), nonce(), body, SELF, SECRET);
	}

	private static MockHttpServletRequest request(String path, String caller, String tenant, String timestamp,
												  String nonce, byte[] body, String target, String secret) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		fill(request, caller, tenant, timestamp, nonce, body, target, secret);
		return request;
	}

	private static void fill(MockHttpServletRequest request, String caller, String tenant, String timestamp,
							 String nonce, byte[] body, String target, String secret) {
		request.setContentType("application/json");
		request.setContent(body);
		Map<String, String> headers = new HashMap<>();
		headers.put(InnerProtocol.HEADER_CALLER, caller);
		headers.put(InnerProtocol.HEADER_TENANT, tenant);
		headers.put(InnerProtocol.HEADER_TIMESTAMP, timestamp);
		headers.put(InnerProtocol.HEADER_NONCE, nonce);
		headers.put(InnerProtocol.HEADER_SIGNATURE, InnerProtocol.signRequest(secret.getBytes(StandardCharsets.UTF_8),
			"POST", request.getRequestURI(), timestamp, nonce, caller, target, tenant, body));
		headers.forEach(request::addHeader);
	}

	private MockHttpServletResponse run(InnerRequestFilter filter, MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		return response;
	}

	private static void assertResponseSigned(MockHttpServletRequest request, MockHttpServletResponse response) {
		String expected = InnerProtocol.signResponse(SECRET.getBytes(StandardCharsets.UTF_8), PATH,
			request.getHeader(InnerProtocol.HEADER_NONCE), CALLER, SELF, response.getContentAsByteArray());

		assertThat(response.getHeader(InnerProtocol.HEADER_SIGNATURE)).isEqualTo(expected);
	}
}
