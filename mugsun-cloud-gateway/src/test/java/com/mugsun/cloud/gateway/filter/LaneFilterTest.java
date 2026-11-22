package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.lane.LaneNames;
import com.mugsun.cloud.gateway.lane.LaneRuleBook;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.support.ClientViewResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 给要转发的请求定灰度标记：用的是按可信代理算出来的客户端地址和域名；算出来的标记记在请求的属性上，没有标记就没有这个属性。
 * 规则本身怎么判断在 LaneRulesTest 里测，经过网关的完整过程在 GatewayLaneTest 里测。
 */
class LaneFilterTest {

	private static final String RULES = """
		lanes:
		  - name: canary
		    client-ips: 203.0.113.7
		  - name: beta
		    hosts: beta.example.com
		header-from: 10.0.0.0/8
		""";

	private final LaneRuleBook book = new LaneRuleBook();

	private final GatewayGuardProperties properties = new GatewayGuardProperties();

	private LaneFilter filter() {
		ServerProperties server = new ServerProperties();
		server.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.NONE);
		return new LaneFilter(book, new ClientViewResolver(properties, server));
	}

	private static MockServerHttpRequest.BaseBuilder<?> from(String remote, String url) {
		return MockServerHttpRequest.get(url).remoteAddress(new InetSocketAddress(remote, 40000));
	}

	/** 过一遍过滤器，返回记在请求上的灰度标记；没有标记返回 null */
	private String laneOf(MockServerHttpRequest.BaseBuilder<?> request) {
		MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
		AtomicInteger passedOn = new AtomicInteger();
		filter().filter(exchange, current -> {
			passedOn.incrementAndGet();
			return Mono.empty();
		}).block();
		assertThat(passedOn).as("请求要接着往下走，而且只走一次").hasValue(1);
		return exchange.getAttribute(LaneNames.LANE_ATTR);
	}

	@Test
	void withoutRulesNoRequestGetsALane() {
		assertThat(laneOf(from("203.0.113.7", "http://beta.example.com/gis/layer/list"))).isNull();
		assertThat(laneOf(from("10.1.1.1", "http://www.example.com/x").header("X-Mugsun-Lane", "canary"))).isNull();
	}

	@Test
	void laneIsDecidedByTheClientAddressAndTheVisitedHost() {
		book.load(RULES);

		assertThat(laneOf(from("203.0.113.7", "http://www.example.com/gis/layer/list"))).isEqualTo("canary");
		assertThat(laneOf(from("198.51.100.1", "http://beta.example.com/gis/layer/list"))).isEqualTo("beta");
		assertThat(laneOf(from("198.51.100.1", "http://BETA.example.com:8443/gis/layer/list"))).isEqualTo("beta");
		assertThat(laneOf(from("198.51.100.1", "http://www.example.com/gis/layer/list"))).isNull();
	}

	@Test
	void headerCountsOnlyFromTheListedAddresses() {
		book.load(RULES);

		assertThat(laneOf(from("10.1.1.1", "http://www.example.com/x").header("X-Mugsun-Lane", "hotfix-1")))
			.isEqualTo("hotfix-1");
		assertThat(laneOf(from("198.51.100.1", "http://www.example.com/x").header("X-Mugsun-Lane", "hotfix-1"))).isNull();
		// 说 base：这次不走灰度，哪怕域名对得上规则
		assertThat(laneOf(from("10.1.1.1", "http://beta.example.com/x").header("X-Mugsun-Lane", "base"))).isNull();
		assertThat(laneOf(from("10.1.1.1", "http://beta.example.com/x"))).isEqualTo("beta");
	}

	/** 同一个头带了两个值：不去猜该认哪一个，当成没带 */
	@Test
	void headerWithMoreThanOneValueIsIgnored() {
		book.load(RULES);

		assertThat(laneOf(from("10.1.1.1", "http://www.example.com/x").header("X-Mugsun-Lane", "hotfix-1", "hotfix-2")))
			.isNull();
		assertThat(laneOf(from("10.1.1.1", "http://beta.example.com/x").header("X-Mugsun-Lane", "hotfix-1", "base")))
			.isEqualTo("beta");
	}

	@Test
	void headerValueIsReadWithoutSurroundingBlanks() {
		book.load(RULES);

		assertThat(laneOf(from("10.1.1.1", "http://www.example.com/x").header("X-Mugsun-Lane", " hotfix-1 ")))
			.isEqualTo("hotfix-1");
	}

	/** 网关前面有反向代理：配了可信代理，用的是代理报上来的客户端地址和域名；没配，所有请求看起来都来自代理 */
	@Test
	void behindATrustedProxyTheAddressAndHostItReportsAreUsed() {
		book.load(RULES);
		MockServerHttpRequest.BaseBuilder<?> viaProxy = from("192.0.2.10", "http://gateway.internal/gis/layer/list")
			.header("X-Forwarded-For", "203.0.113.7");
		assertThat(laneOf(viaProxy)).isNull();

		properties.setTrustedProxies("192.0.2.10/32");

		assertThat(laneOf(from("192.0.2.10", "http://gateway.internal/gis/layer/list")
			.header("X-Forwarded-For", "203.0.113.7"))).isEqualTo("canary");
		assertThat(laneOf(from("192.0.2.10", "http://gateway.internal/gis/layer/list")
			.header("X-Forwarded-For", "198.51.100.1").header("X-Forwarded-Host", "beta.example.com"))).isEqualTo("beta");
		// 不是从可信代理来的：它自己报的地址不算
		assertThat(laneOf(from("198.51.100.1", "http://gateway.internal/gis/layer/list")
			.header("X-Forwarded-For", "203.0.113.7"))).isNull();
	}

	/** 请求上已经有这个属性（别的代码放的）：以这里算的为准，算出来没有标记就拿掉 */
	@Test
	void attributeAlreadyOnTheRequestIsOverwrittenOrRemoved() {
		MockServerWebExchange exchange = MockServerWebExchange.from(from("198.51.100.1", "http://www.example.com/x").build());
		exchange.getAttributes().put(LaneNames.LANE_ATTR, "stale");

		filter().filter(exchange, current -> Mono.empty()).block();
		assertThat(exchange.getAttributes()).doesNotContainKey(LaneNames.LANE_ATTR);

		book.load(RULES);
		exchange = MockServerWebExchange.from(from("203.0.113.7", "http://www.example.com/x").build());
		exchange.getAttributes().put(LaneNames.LANE_ATTR, "stale");

		filter().filter(exchange, current -> Mono.empty()).block();
		assertThat(exchange.getAttributes()).containsEntry(LaneNames.LANE_ATTR, "canary");
	}

	/** 排在限流之后（被拒绝的请求不用算），选实例之前（选实例要看标记） */
	@Test
	void runsAfterRateLimitingAndBeforeAnInstanceIsChosen() {
		assertThat(filter().getOrder()).isEqualTo(LaneFilter.ORDER)
			.isGreaterThan(RateLimitFilter.ORDER)
			.isLessThan(ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER);
	}
}
