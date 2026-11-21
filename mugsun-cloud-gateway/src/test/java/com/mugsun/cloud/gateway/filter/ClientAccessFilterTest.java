package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.error.GatewayRejectedException;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.support.ClientViewResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 按客户端地址限制转发的三条规则：黑名单、白名单、不对外开放的路径。经过网关的完整过程在 GatewayClientAccessTest 里测。
 */
class ClientAccessFilterTest {

	private static final String OUTSIDER = "192.0.2.1";

	@Test
	void nothingConfiguredMeansNoRestriction() {
		ClientAccessFilter filter = filter(properties -> {
		});

		assertThat(rejected(filter, OUTSIDER, "/actuator/health")).isFalse();
		assertThat(rejected(filter, null, "/ping")).isFalse();
	}

	@Test
	void addressOnDenyListIsRejected() {
		ClientAccessFilter filter = filter(properties -> properties.setDenyIps("203.0.113.0/24, 2001:db8::/32"));

		assertThat(rejected(filter, "203.0.113.9", "/ping")).isTrue();
		assertThat(rejected(filter, "2001:db8::1", "/ping")).isTrue();
		assertThat(rejected(filter, "203.0.114.9", "/ping")).isFalse();
		assertThat(rejected(filter, "2001:db9::1", "/ping")).isFalse();
	}

	@Test
	void allowListRejectsEveryoneElse() {
		ClientAccessFilter filter = filter(properties -> properties.setAllowIps("198.51.100.0/24,10.1.2.3"));

		assertThat(rejected(filter, "198.51.100.7", "/ping")).isFalse();
		assertThat(rejected(filter, "10.1.2.3", "/ping")).isFalse();
		assertThat(rejected(filter, "10.1.2.4", "/ping")).isTrue();
		assertThat(rejected(filter, OUTSIDER, "/ping")).isTrue();
		// 白名单里只有 IPv4 的地址段，IPv6 的客户端不在里面
		assertThat(rejected(filter, "2001:db8::1", "/ping")).isTrue();
		// 算不出客户端地址的不放行
		assertThat(rejected(filter, null, "/ping")).isTrue();
	}

	@Test
	void denyListWinsOverAllowList() {
		ClientAccessFilter filter = filter(properties -> {
			properties.setAllowIps("198.51.100.0/24");
			properties.setDenyIps("198.51.100.66");
		});

		assertThat(rejected(filter, "198.51.100.7", "/ping")).isFalse();
		assertThat(rejected(filter, "198.51.100.66", "/ping")).isTrue();
	}

	@Test
	void restrictedPathNeedsAnAllowedAddress() {
		ClientAccessFilter filter = filter(properties -> {
			properties.setRestrictedPaths("/actuator,/v3/api-docs");
			properties.setRestrictedAllowIps("10.0.0.0/8");
		});

		assertThat(rejected(filter, OUTSIDER, "/actuator/health")).isTrue();
		assertThat(rejected(filter, OUTSIDER, "/%61ctuator/health")).isTrue();
		assertThat(rejected(filter, OUTSIDER, "/v3/api-docs")).isTrue();
		assertThat(rejected(filter, null, "/actuator/health")).isTrue();
		assertThat(rejected(filter, "10.20.30.40", "/actuator/health")).isFalse();
		// 别的路径不受影响
		assertThat(rejected(filter, OUTSIDER, "/ping")).isFalse();
		assertThat(rejected(filter, OUTSIDER, "/actuatorx")).isFalse();
		assertThat(rejected(filter, null, "/ping")).isFalse();
	}

	@Test
	void restrictedPathWithoutAllowedAddressesIsClosedToEveryone() {
		ClientAccessFilter filter = filter(properties -> properties.setRestrictedPaths("/actuator"));

		assertThat(rejected(filter, "127.0.0.1", "/actuator/health")).isTrue();
		assertThat(rejected(filter, "10.20.30.40", "/actuator")).isTrue();
		assertThat(rejected(filter, "10.20.30.40", "/ping")).isFalse();
	}

	@Test
	void pathAfterRouteRewritingIsCheckedToo() {
		ClientAccessFilter filter = filter(properties -> properties.setRestrictedPaths("/actuator"));
		MockServerWebExchange exchange = exchange(OUTSIDER, "/sys/actuator/health");
		// 路由去掉了第一段，发给服务的是 /actuator/health
		exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,
			URI.create("http://10.0.0.5:8101/actuator/health"));

		assertThat(rejected(filter, exchange)).isTrue();
		assertThat(rejected(filter, OUTSIDER, "/sys/actuator/health")).isFalse();
	}

	@Test
	void forwardedHeaderFromAnUntrustedClientDoesNotCount() {
		ClientAccessFilter filter = filter(properties -> {
			properties.setRestrictedPaths("/actuator");
			properties.setRestrictedAllowIps("10.0.0.0/8");
			properties.setDenyIps("203.0.113.9");
		});

		// 没配可信代理：客户端自称是名单里的地址，不算数
		assertThat(rejected(filter, exchange(OUTSIDER, "/actuator/health", "10.20.30.40"))).isTrue();
		// 黑名单里的地址自称是别的地址，也不算数
		assertThat(rejected(filter, exchange("203.0.113.9", "/ping", "10.20.30.40"))).isTrue();
	}

	@Test
	void forwardedHeaderFromATrustedProxyCounts() {
		ClientAccessFilter filter = filter(properties -> {
			properties.setTrustedProxies("192.0.2.0/24");
			properties.setRestrictedPaths("/actuator");
			properties.setRestrictedAllowIps("10.0.0.0/8");
			properties.setDenyIps("203.0.113.9");
		});

		assertThat(rejected(filter, exchange(OUTSIDER, "/actuator/health", "10.20.30.40"))).isFalse();
		assertThat(rejected(filter, exchange(OUTSIDER, "/actuator/health", "198.51.100.7"))).isTrue();
		assertThat(rejected(filter, exchange(OUTSIDER, "/ping", "203.0.113.9"))).isTrue();
		// 代理用追加的方式写时，左边的值是客户端自己填的，不能当客户端地址
		assertThat(rejected(filter, exchange(OUTSIDER, "/actuator/health", "10.20.30.40, 198.51.100.7"))).isTrue();
	}

	@Test
	void rejectionIs403() {
		ClientAccessFilter filter = filter(properties -> properties.setDenyIps("203.0.113.9"));

		assertThatThrownBy(() -> filter.filter(exchange("203.0.113.9", "/ping"), current -> Mono.empty()).block())
			.isInstanceOfSatisfying(GatewayRejectedException.class,
				error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
	}

	@Test
	void runsAfterTheInnerPathCheckAndBeforeAnInstanceIsChosen() {
		GatewayGuardProperties properties = new GatewayGuardProperties();
		ClientViewResolver resolver = resolver(properties);

		assertThat(new ClientAccessFilter(properties, resolver).getOrder())
			.isGreaterThan(new InnerPathGuardFilter(properties, resolver).getOrder())
			.isLessThan(ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER);
	}

	@Test
	void invalidAddressRangeFailsFast() {
		assertThatThrownBy(() -> filter(properties -> properties.setAllowIps("10.0.0.0/33")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mugsun.gateway.allow-ips")
			.hasMessageContaining("10.0.0.0/33");
		assertThatThrownBy(() -> filter(properties -> properties.setDenyIps("203.0.113.9,example.com")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mugsun.gateway.deny-ips");
		assertThatThrownBy(() -> filter(properties -> properties.setRestrictedAllowIps("10.0.0")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mugsun.gateway.restricted-allow-ips");
	}

	@Test
	void invalidRestrictedPathFailsFast() {
		assertThatThrownBy(() -> filter(properties -> properties.setRestrictedPaths("/actuator,/a/*")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mugsun.gateway.restricted-paths");
	}

	private static ClientAccessFilter filter(Consumer<GatewayGuardProperties> customizer) {
		GatewayGuardProperties properties = new GatewayGuardProperties();
		customizer.accept(properties);
		return new ClientAccessFilter(properties, resolver(properties));
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

	private static boolean rejected(ClientAccessFilter filter, String clientIp, String rawPath) {
		return rejected(filter, exchange(clientIp, rawPath));
	}

	private static boolean rejected(ClientAccessFilter filter, MockServerWebExchange exchange) {
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
}
