package com.mugsun.cloud.gateway.support;

import com.mugsun.boot.common.web.IpCidr;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 按配置的可信代理，从一次请求里算出客户端的真实地址和它访问时用的协议、主机名、端口。
 *
 * <p>前提是请求的连接地址没被别的机制改过，所以网关自己的 {@code server.forward-headers-strategy} 必须是 none：
 * 设成 native 或 framework（在 Kubernetes 等平台上不写时默认就是 native）后，容器会不分来源地采信转发头，
 * 任何客户端都能用 X-Forwarded-For 冒充别的地址。这里在启动时检查，不对就拒绝启动。
 */
@Component
public class ClientViewResolver {

	private static final Logger log = LoggerFactory.getLogger(ClientViewResolver.class);

	private final List<IpCidr> trustedProxies;

	public ClientViewResolver(GatewayGuardProperties properties, ServerProperties serverProperties) {
		ServerProperties.ForwardHeadersStrategy strategy = serverProperties.getForwardHeadersStrategy();
		if (strategy != ServerProperties.ForwardHeadersStrategy.NONE) {
			throw new IllegalStateException("网关的 server.forward-headers-strategy 必须是 none（现在是 "
				+ (strategy == null ? "未设置" : strategy.name().toLowerCase(Locale.ROOT)) + "），否则客户端可以用 X-Forwarded-For 冒充别的地址。"
				+ "网关前面有反向代理时，把代理的地址写进 mugsun.gateway.trusted-proxies（环境变量 MUGSUN_OPENAPI_TRUSTED_PROXIES）");
		}
		this.trustedProxies = trusted(properties.getTrustedProxies());
	}

	/** 和 mugsun-boot 一样，写错的条目记一条警告后忽略。每一条先经 {@link IpRanges} 看写法，写错的不会被拿去查 DNS */
	private static List<IpCidr> trusted(String text) {
		List<IpCidr> result = new ArrayList<>();
		for (String item : (text == null ? "" : text).split("[,;\\s]+")) {
			if (item.isEmpty()) {
				continue;
			}
			IpCidr range = IpRanges.range(item);
			if (range == null) {
				log.warn("忽略 mugsun.gateway.trusted-proxies 里不合法的一条：{}", item);
			} else {
				result.add(range);
			}
		}
		return List.copyOf(result);
	}

	public ClientView resolve(ServerHttpRequest request) {
		return ClientView.resolve(remoteAddr(request), request.getURI(), request.getHeaders(), trustedProxies);
	}

	private static String remoteAddr(ServerHttpRequest request) {
		InetSocketAddress remote = request.getRemoteAddress();
		if (remote == null) {
			return null;
		}
		return remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
	}
}
