package com.mugsun.cloud.gateway.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关自身的配置（前缀 {@code mugsun.gateway}）。
 */
@ConfigurationProperties(prefix = "mugsun.gateway")
public class GatewayGuardProperties {

	/**
	 * 网关前面的可信代理（Nginx、负载均衡）的地址段，逗号分隔的 CIDR，例如 {@code 10.0.0.0/8,192.168.1.10/32}。
	 * 只有请求直接来自这些地址时，网关才采信它带来的 X-Forwarded-* 头；留空表示网关直接面向客户端，一概不采信。
	 */
	private String trustedProxies = "";

	/**
	 * 服务之间互相调用的内部接口的路径前缀。网关对外一律不转发这个前缀下的请求。
	 */
	private String innerPath = "/inner";

	public String getTrustedProxies() {
		return trustedProxies;
	}

	public void setTrustedProxies(String trustedProxies) {
		this.trustedProxies = trustedProxies == null ? "" : trustedProxies;
	}

	public String getInnerPath() {
		return innerPath;
	}

	public void setInnerPath(String innerPath) {
		this.innerPath = innerPath;
	}
}
