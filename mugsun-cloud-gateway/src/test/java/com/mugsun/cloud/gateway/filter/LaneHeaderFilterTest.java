package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.lane.LaneNames;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写给后面服务的灰度请求头：只能是网关算出来的那个标记；客户端自己带的一律不转发，有没有标记都一样。
 */
class LaneHeaderFilterTest {

	private final LaneHeaderFilter filter = new LaneHeaderFilter();

	private static MockServerWebExchange exchange(String lane) {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/gis/layer/list"));
		if (lane != null) {
			exchange.getAttributes().put(LaneNames.LANE_ATTR, lane);
		}
		return exchange;
	}

	private static HttpHeaders headers(String... namesAndValues) {
		HttpHeaders headers = new HttpHeaders();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			headers.add(namesAndValues[i], namesAndValues[i + 1]);
		}
		return headers;
	}

	@Test
	void headerSentByTheClientIsDroppedWhenTheRequestHasNoLane() {
		HttpHeaders output = filter.filter(headers("Accept", "application/json", "X-Mugsun-Lane", "canary"), exchange(null));

		assertThat(output.get("X-Mugsun-Lane")).isNull();
		assertThat(output.get("Accept")).containsExactly("application/json");
	}

	@Test
	void headerSentByTheClientIsReplacedByTheLaneTheGatewayDecided() {
		HttpHeaders output = filter.filter(headers("X-Mugsun-Lane", "beta", "X-Mugsun-Lane", "v2"), exchange("canary"));

		assertThat(output.get("X-Mugsun-Lane")).containsExactly("canary");
	}

	@Test
	void headerNameIsMatchedWhateverItsCase() {
		assertThat(filter.filter(headers("x-mugsun-lane", "canary"), exchange(null)).isEmpty()).isTrue();
		assertThat(filter.filter(headers("X-MUGSUN-LANE", "beta"), exchange("canary")).get("X-Mugsun-Lane"))
			.containsExactly("canary");
	}

	@Test
	void laneIsWrittenWhenTheClientSentNothing() {
		HttpHeaders output = filter.filter(headers("Accept", "application/json"), exchange("canary"));

		assertThat(output.get("X-Mugsun-Lane")).containsExactly("canary");
		assertThat(output.get("Accept")).containsExactly("application/json");
	}

	@Test
	void everyOtherHeaderIsPassedOnWithAllItsValues() {
		HttpHeaders input = headers("Accept", "application/json", "Cookie", "a=1", "Cookie", "b=2", "X-Trace-Id", "abc",
			"X-Mugsun-Lane-Extra", "kept");

		HttpHeaders output = filter.filter(input, exchange(null));

		assertThat(output.get("Cookie")).containsExactly("a=1", "b=2");
		assertThat(output.get("X-Trace-Id")).containsExactly("abc");
		// 只删名字整个相同的那一个头
		assertThat(output.get("X-Mugsun-Lane-Extra")).containsExactly("kept");
		assertThat(output.size()).isEqualTo(4);
	}

	/** 只管发给服务的请求头，不管服务回来的响应头 */
	@Test
	void appliesToRequestHeadersOnly() {
		assertThat(filter.supports(HttpHeadersFilter.Type.REQUEST)).isTrue();
		assertThat(filter.supports(HttpHeadersFilter.Type.RESPONSE)).isFalse();
	}
}
