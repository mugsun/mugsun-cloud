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

	private final AccessLog accessLog = new AccessLog();

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

	public AccessLog getAccessLog() {
		return accessLog;
	}

	/**
	 * 访问日志（前缀 {@code mugsun.gateway.access-log}）：每个请求结束时记一行，日志名是 ACCESS。
	 */
	public static class AccessLog {

		public enum Mode {
			/** 每个请求都记 */
			ALL,
			/** 只记有问题的：5xx、转发出错、慢请求 */
			PROBLEMS,
			/** 不记。不叫 off：YAML 会把没加引号的 off 当成布尔值 */
			NONE
		}

		/** 记哪些请求 */
		private Mode mode = Mode.ALL;

		/**
		 * 从收到请求到开始回应超过这么多毫秒算慢请求，这一行按警告级别记；0 表示不按耗时区分。
		 * 不看整个请求的时长：下载大文件、流式输出、WebSocket 连接本来就会持续很久。
		 */
		private long slowMillis = 3000;

		public Mode getMode() {
			return mode;
		}

		public void setMode(Mode mode) {
			this.mode = mode == null ? Mode.ALL : mode;
		}

		public long getSlowMillis() {
			return slowMillis;
		}

		public void setSlowMillis(long slowMillis) {
			this.slowMillis = slowMillis;
		}
	}
}
