package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.lane.LaneNames;
import com.mugsun.cloud.gateway.lane.LaneRuleBook;
import com.mugsun.cloud.gateway.support.ClientView;
import com.mugsun.cloud.gateway.support.ClientViewResolver;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 灰度发布：给每个要转发的请求定灰度标记。按 {@link LaneRuleBook} 里正在用的规则算，算出来的标记记在请求的属性上
 * （{@link LaneNames#LANE_ATTR}），没有标记就没有这个属性。后面三处用它：选实例（没有标记的请求只发给正式实例，
 * 有标记的先发给同一个标记的灰度实例，见 lane 包的 LaneInstanceSupplier）、写给服务的请求头（{@link LaneHeaderFilter}）、
 * 访问日志（{@link AccessLogFilter}）。
 *
 * <p>谁走灰度由网关这边的规则定，不由客户端定：客户端带来的 {@code X-Mugsun-Lane} 只有在它的地址写进了规则的
 * {@code header-from} 时才认（给测试人员和压测工具用），其余的一概不看。灰度只是分流，不是权限：不要指望用它拦住谁。
 *
 * <p>客户端地址和域名用 {@link ClientViewResolver} 算。网关前面有反向代理时要先配好可信代理，否则所有请求的地址都是代理的地址：
 * 按地址的规则要么全中要么全不中，按比例的规则也一样。
 *
 * <p>管的是转发。网关自己的健康检查、由网关自己应答的跨域预检、没有路由可转的请求不经过这里。
 */
@Component
public class LaneFilter implements GlobalFilter, Ordered {

	/** 排在限流之后：被拒绝的请求不用算。在选实例之前：选实例要看标记 */
	public static final int ORDER = RateLimitFilter.ORDER + 1;

	private final LaneRuleBook book;
	private final ClientViewResolver clientViewResolver;

	public LaneFilter(LaneRuleBook book, ClientViewResolver clientViewResolver) {
		this.book = book;
		this.clientViewResolver = clientViewResolver;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		String lane = null;
		if (book.hasRules()) {
			ServerHttpRequest request = exchange.getRequest();
			ClientView view = clientViewResolver.resolve(request);
			lane = book.laneFor(view.clientIp(), view.host(), requested(request));
		}
		if (lane == null) {
			exchange.getAttributes().remove(LaneNames.LANE_ATTR);
		} else {
			exchange.getAttributes().put(LaneNames.LANE_ATTR, lane);
		}
		return chain.filter(exchange);
	}

	/** 请求头里带的标记。带了不止一个值时当成没带，不去猜该认哪一个 */
	private static String requested(ServerHttpRequest request) {
		List<String> values = request.getHeaders().getOrEmpty(LaneNames.HEADER);
		return values.size() == 1 && values.get(0) != null ? values.get(0).strip() : null;
	}

	@Override
	public int getOrder() {
		return ORDER;
	}
}
