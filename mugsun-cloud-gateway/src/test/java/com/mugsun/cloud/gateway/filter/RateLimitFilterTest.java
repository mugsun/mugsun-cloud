package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.error.GatewayRejectedException;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.support.ClientViewResolver;
import com.mugsun.cloud.gateway.support.TraceIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 限流：按客户端地址数请求，超过限额的拒绝。时钟是假的。经过网关的完整过程在 GatewayRateLimitTest 里测。
 */
@ExtendWith(OutputCaptureExtension.class)
class RateLimitFilterTest {

	private static final String ALICE = "192.0.2.1";
	private static final String BOB = "192.0.2.2";

	private final AtomicLong now = new AtomicLong(5_000_000_000L);
	private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();

	@Test
	void nothingConfiguredMeansNoLimit() {
		RateLimitFilter filter = filter(properties -> {
		});

		for (int i = 0; i < 500; i++) {
			assertThat(rejected(filter, ALICE, "/ping")).isFalse();
		}
	}

	@Test
	void requestsOverTheLimitAreRejectedUntilTokensComeBack() {
		RateLimitFilter filter = filter(properties -> properties.setRateLimit("3/60s"));

		pass(filter, ALICE, "/ping", 3);
		assertThat(rejected(filter, ALICE, "/ping")).isTrue();
		assertThat(rejected(filter, ALICE, "/another/path")).isTrue();

		now.addAndGet(Duration.ofSeconds(20).toNanos());
		assertThat(rejected(filter, ALICE, "/ping")).isFalse();
		assertThat(rejected(filter, ALICE, "/ping")).isTrue();
	}

	@Test
	void rejectionIs429AndTellsWhenToRetry() {
		RateLimitFilter filter = filter(properties -> properties.setRateLimit("3/60s"));
		pass(filter, ALICE, "/ping", 3);

		assertThatThrownBy(() -> filter.filter(exchange(ALICE, "/ping"), current -> Mono.empty()).block())
			.isInstanceOfSatisfying(GatewayRejectedException.class, error -> {
				assertThat(error.getStatusCode().value()).isEqualTo(429);
				assertThat(error.getReason()).isEqualTo("请求过于频繁，请稍后再试");
				assertThat(error.getHeaders().get("Retry-After")).containsExactly("20");
			});

		// 还差半秒：往上取整，不能让客户端来早了
		now.addAndGet(Duration.ofMillis(19_500).toNanos());
		assertThatThrownBy(() -> filter.filter(exchange(ALICE, "/ping"), current -> Mono.empty()).block())
			.isInstanceOfSatisfying(GatewayRejectedException.class,
				error -> assertThat(error.getHeaders().get("Retry-After")).containsExactly("1"));
	}

	@Test
	void everyAddressIsCountedOnItsOwn() {
		RateLimitFilter filter = filter(properties -> properties.setRateLimit("3/60s"));

		pass(filter, ALICE, "/ping", 3);
		assertThat(rejected(filter, ALICE, "/ping")).isTrue();
		pass(filter, BOB, "/ping", 3);
		assertThat(rejected(filter, BOB, "/ping")).isTrue();
	}

	@Test
	void ipv6IsCountedBySlash64() {
		RateLimitFilter filter = filter(properties -> properties.setRateLimit("3/60s"));

		assertThat(rejected(filter, "2001:db8:1:2::1", "/ping")).isFalse();
		assertThat(rejected(filter, "2001:db8:1:2::2", "/ping")).isFalse();
		assertThat(rejected(filter, "2001:db8:1:2:ffff:ffff:ffff:ffff", "/ping")).isFalse();
		// 同一个 /64 里换地址，还是同一个桶
		assertThat(rejected(filter, "2001:db8:1:2:abcd::9", "/ping")).isTrue();
		// 另一个 /64 是另一个桶
		assertThat(rejected(filter, "2001:db8:1:3::1", "/ping")).isFalse();
	}

	@Test
	void requestsWithoutAnAddressShareOneBucket() {
		RateLimitFilter filter = filter(properties -> properties.setRateLimit("3/60s"));

		pass(filter, null, "/ping", 3);
		assertThat(rejected(filter, null, "/ping")).isTrue();
		assertThat(rejected(filter, ALICE, "/ping")).isFalse();
	}

	@Test
	void forwardedHeaderFromAnUntrustedClientDoesNotGetAFreshBucket() {
		RateLimitFilter filter = filter(properties -> properties.setRateLimit("3/60s"));

		// 没配可信代理：客户端每次自称一个新地址，不算数
		for (int i = 0; i < 3; i++) {
			assertThat(rejected(filter, exchange(ALICE, "/ping", "198.51.100." + i))).isFalse();
		}
		assertThat(rejected(filter, exchange(ALICE, "/ping", "198.51.100.200"))).isTrue();
	}

	@Test
	void forwardedHeaderFromATrustedProxyTellsClientsApart() {
		RateLimitFilter filter = filter(properties -> {
			properties.setTrustedProxies("192.0.2.0/24");
			properties.setRateLimit("3/60s");
		});

		for (int i = 0; i < 3; i++) {
			assertThat(rejected(filter, exchange(ALICE, "/ping", "198.51.100.7"))).isFalse();
		}
		assertThat(rejected(filter, exchange(ALICE, "/ping", "198.51.100.7"))).isTrue();
		// 同一个代理转来的另一个客户端不受影响
		assertThat(rejected(filter, exchange(ALICE, "/ping", "198.51.100.8"))).isFalse();
		// 客户端自己在左边填的地址不算数，认的是代理写的最右边那个
		assertThat(rejected(filter, exchange(ALICE, "/ping", "203.0.113.50, 198.51.100.7"))).isTrue();
	}

	@Test
	void exemptAddressesAreNotLimited() {
		RateLimitFilter filter = filter(properties -> {
			properties.setRateLimit("3/60s");
			properties.setRateLimitExemptIps("198.51.100.0/24, 2001:db8:ffff::/48");
		});

		for (int i = 0; i < 50; i++) {
			assertThat(rejected(filter, "198.51.100.7", "/ping")).isFalse();
			assertThat(rejected(filter, "2001:db8:ffff:1::5", "/ping")).isFalse();
		}
		pass(filter, ALICE, "/ping", 3);
		assertThat(rejected(filter, ALICE, "/ping")).isTrue();
	}

	@Test
	void pathWithItsOwnLimitIsCountedSeparately() {
		RateLimitFilter filter = filter(properties -> {
			properties.setRateLimit("3/60s");
			properties.setRateLimitPaths("/system/auth=2/60s");
		});

		pass(filter, ALICE, "/system/auth/login", 2);
		assertThat(rejected(filter, ALICE, "/system/auth/login")).isTrue();
		assertThat(rejected(filter, ALICE, "/SYSTEM/auth;x=1/captcha")).isTrue();
		// 别的路径用默认限额，桶是分开的
		pass(filter, ALICE, "/system/user/list", 3);
		assertThat(rejected(filter, ALICE, "/system/user/list")).isTrue();
	}

	@Test
	void pathLimitsWorkWithoutADefaultLimit() {
		RateLimitFilter filter = filter(properties -> properties.setRateLimitPaths("/system/auth=2/60s"));

		pass(filter, ALICE, "/system/auth/login", 2);
		assertThat(rejected(filter, ALICE, "/system/auth/login")).isTrue();
		for (int i = 0; i < 50; i++) {
			assertThat(rejected(filter, ALICE, "/system/user/list")).isFalse();
		}
	}

	@Test
	void unlimitedPathIsNotCounted() {
		RateLimitFilter filter = filter(properties -> {
			properties.setRateLimit("3/60s");
			properties.setRateLimitPaths("/system/gis/tile=unlimited");
		});

		for (int i = 0; i < 50; i++) {
			assertThat(rejected(filter, ALICE, "/system/gis/tile/3/2/1.png")).isFalse();
		}
		// 也没有占掉默认限额
		pass(filter, ALICE, "/system/gis/layer", 3);
		assertThat(rejected(filter, ALICE, "/system/gis/layer")).isTrue();
	}

	@Test
	void pathAfterRouteRewritingIsCheckedToo() {
		RateLimitFilter filter = filter(properties -> properties.setRateLimitPaths("/auth=1/60s"));

		for (int i = 0; i < 2; i++) {
			MockServerWebExchange exchange = exchange(ALICE, "/sys/auth/login");
			// 路由去掉了第一段，发给服务的是 /auth/login
			exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,
				URI.create("http://10.0.0.5:8101/auth/login"));
			assertThat(rejected(filter, exchange)).isEqualTo(i == 1);
		}
	}

	@Test
	void runsAfterTheClientAccessCheckAndBeforeAnInstanceIsChosen() {
		GatewayGuardProperties properties = new GatewayGuardProperties();
		ClientViewResolver resolver = resolver(properties);

		assertThat(new RateLimitFilter(properties, resolver).getOrder())
			.isGreaterThan(new ClientAccessFilter(properties, resolver).getOrder())
			.isLessThan(ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER);
	}

	@Test
	void invalidSettingsFailFast() {
		assertThatThrownBy(() -> filter(properties -> properties.setRateLimit("600")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mugsun.gateway.rate-limit");
		assertThatThrownBy(() -> filter(properties -> properties.setRateLimitPaths("/system/auth=30/60s,/v1")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mugsun.gateway.rate-limit-paths");
		assertThatThrownBy(() -> filter(properties -> properties.setRateLimitExemptIps("10.0.0.0/33")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mugsun.gateway.rate-limit-exempt-ips")
			.hasMessageContaining("10.0.0.0/33");
		// 像地址、其实不是地址的写法：不能被查成别的地址、悄悄收进名单
		assertThatThrownBy(() -> filter(properties -> properties.setRateLimitExemptIps("198.51.100.0/24, 10.0.0.300")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mugsun.gateway.rate-limit-exempt-ips")
			.hasMessageContaining("10.0.0.300");
	}

	@Test
	void startupLineSaysWhatIsLimited(CapturedOutput output) {
		filter(properties -> {
			properties.setTrustedProxies("10.0.0.0/8");
			properties.setRateLimit("600/1m");
			properties.setRateLimitPaths("/system/auth=30/60s,/v1=60/60s");
			properties.setRateLimitExemptIps("198.51.100.0/24");
		});

		assertThat(lines(output, "限流已启用")).singleElement().asString()
			.contains("默认限额 600/60s，单独定了限额的路径前缀 2 个，不限流的地址 1 段")
			.doesNotContain("trusted-proxies");
	}

	@Test
	void startupLineWarnsWhenNoTrustedProxyIsConfigured(CapturedOutput output) {
		filter(properties -> properties.setRateLimitPaths("/system/auth=30/60s"));

		assertThat(lines(output, "限流已启用")).singleElement().asString()
			.contains("默认限额 没有，单独定了限额的路径前缀 1 个")
			.contains("没有配可信代理（mugsun.gateway.trusted-proxies，环境变量 MUGSUN_OPENAPI_TRUSTED_PROXIES）");
	}

	@Test
	void trustedProxiesMadeOfSeparatorsOnlyCountAsNotConfigured(CapturedOutput output) {
		// 变量里只剩下分隔符（比如拼变量时两边都是空的）：一条可信代理也没有，提醒照样要给
		filter(properties -> {
			properties.setTrustedProxies(" , ;");
			properties.setRateLimit("600/1m");
		});

		assertThat(lines(output, "限流已启用")).singleElement().asString().contains("没有配可信代理（");
	}

	@Test
	void nothingIsLoggedAtStartupWhenOff(CapturedOutput output) {
		filter(properties -> properties.setRateLimitExemptIps("198.51.100.0/24"));

		assertThat(lines(output, "限流")).isEmpty();
	}

	@Test
	void firstRejectionIsLoggedWithRuleClientAndTraceId(CapturedOutput output) {
		RateLimitFilter filter = filter(properties -> {
			properties.setTrustedProxies("10.0.0.0/8");
			properties.setRateLimitPaths("/system/auth=2/60s");
		});
		pass(filter, ALICE, "/system/auth/login", 2);

		MockServerWebExchange exchange = exchange(ALICE, "/system/auth/login");
		exchange.getAttributes().put(TraceIds.ATTRIBUTE, "trace-0001");
		assertThat(rejected(filter, exchange)).isTrue();
		assertThat(rejected(filter, ALICE, "/system/auth/login")).isTrue();

		assertThat(lines(output, "开始限流")).singleElement().asString()
			.contains(" WARN ")
			.contains("规则 /system/auth=2/60s，客户端 192.0.2.1，GET /system/auth/login，请求编号 trace-0001。")
			.doesNotContain("X-Forwarded-For");
	}

	@Test
	void rejectionLogHintsAtMissingTrustedProxies(CapturedOutput output) {
		RateLimitFilter filter = filter(properties -> properties.setRateLimit("1/60s"));

		// 没配可信代理，请求却带着 X-Forwarded-For：多半是前面有代理，所有人都被算成了代理这一个地址
		assertThat(rejected(filter, exchange("10.0.0.9", "/ping", "198.51.100.7"))).isFalse();
		assertThat(rejected(filter, exchange("10.0.0.9", "/ping", "198.51.100.8"))).isTrue();

		assertThat(lines(output, "开始限流")).singleElement().asString()
			.contains("客户端 10.0.0.9，")
			.contains("这个请求带着 X-Forwarded-For，而可信代理（mugsun.gateway.trusted-proxies，"
				+ "环境变量 MUGSUN_OPENAPI_TRUSTED_PROXIES）没有配");
	}

	@Test
	void requestWithoutAnAddressIsNamedInTheLog(CapturedOutput output) {
		RateLimitFilter filter = filter(properties -> properties.setRateLimit("1/60s"));

		pass(filter, null, "/ping", 1);
		assertThat(rejected(filter, null, "/ping")).isTrue();

		assertThat(lines(output, "开始限流")).singleElement().asString().contains("客户端 （算不出地址），");
	}

	private RateLimitFilter filter(Consumer<GatewayGuardProperties> customizer) {
		GatewayGuardProperties properties = new GatewayGuardProperties();
		customizer.accept(properties);
		return new RateLimitFilter(properties, resolver(properties), now::get, scheduler);
	}

	private static ClientViewResolver resolver(GatewayGuardProperties properties) {
		ServerProperties server = new ServerProperties();
		server.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.NONE);
		return new ClientViewResolver(properties, server);
	}

	/** 路径按原样传，不再编码一遍 */
	private static MockServerWebExchange exchange(String clientIp, String rawPath) {
		return exchange(clientIp, rawPath, null);
	}

	private static MockServerWebExchange exchange(String clientIp, String rawPath, String forwardedFor) {
		MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.method(HttpMethod.GET,
			URI.create("http://gateway.test" + rawPath));
		if (clientIp != null) {
			request.remoteAddress(new InetSocketAddress(clientIp, 40000));
		}
		if (forwardedFor != null) {
			request.header("X-Forwarded-For", forwardedFor);
		}
		return MockServerWebExchange.from(request);
	}

	private static void pass(RateLimitFilter filter, String clientIp, String rawPath, int times) {
		for (int i = 0; i < times; i++) {
			assertThat(rejected(filter, clientIp, rawPath)).as("第 %d 个请求", i + 1).isFalse();
		}
	}

	private static boolean rejected(RateLimitFilter filter, String clientIp, String rawPath) {
		return rejected(filter, exchange(clientIp, rawPath));
	}

	private static boolean rejected(RateLimitFilter filter, MockServerWebExchange exchange) {
		AtomicBoolean forwarded = new AtomicBoolean();
		try {
			filter.filter(exchange, current -> {
				forwarded.set(true);
				return Mono.empty();
			}).block();
		} catch (GatewayRejectedException e) {
			assertThat(forwarded).isFalse();
			return true;
		}
		assertThat(forwarded).isTrue();
		return false;
	}

	private static List<String> lines(CapturedOutput output, String text) {
		return output.getAll().lines().filter(line -> line.contains(text)).toList();
	}
}
