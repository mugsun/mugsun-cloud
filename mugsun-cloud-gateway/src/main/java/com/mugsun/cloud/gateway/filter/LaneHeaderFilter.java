package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.lane.LaneNames;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Map;

/**
 * 把这次请求的灰度标记交给后面的服务（普通请求和 WebSocket 握手都经过这里）：请求头 {@code X-Mugsun-Lane}。
 * 服务在处理这个请求的过程中调用别的服务时，按这个标记接着选实例。
 *
 * <p>客户端自己带来的这个头一律不转发，有没有灰度规则都一样：写给服务的只能是网关按规则算出来的那个（{@link LaneFilter}）。
 * 不这样的话，任何人加一个请求头，就能让服务之间的调用去找还没验证过的灰度实例。
 */
@Component
public class LaneHeaderFilter implements HttpHeadersFilter, Ordered {

	@Override
	public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
		String lane = exchange.getAttribute(LaneNames.LANE_ATTR);
		HttpHeaders output = new HttpHeaders();
		for (Map.Entry<String, List<String>> entry : input.headerSet()) {
			if (!LaneNames.HEADER.equalsIgnoreCase(entry.getKey())) {
				output.addAll(entry.getKey(), entry.getValue());
			}
		}
		if (lane != null) {
			output.set(LaneNames.HEADER, lane);
		}
		return output;
	}

	@Override
	public int getOrder() {
		return 0;
	}
}
