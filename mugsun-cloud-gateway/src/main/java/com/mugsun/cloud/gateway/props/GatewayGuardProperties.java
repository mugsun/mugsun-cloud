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

	/**
	 * 黑名单：客户端地址落在这些地址段里的请求一律不转发。逗号分隔的 CIDR，留空表示没有黑名单。
	 */
	private String denyIps = "";

	/**
	 * 白名单：不为空时，只转发客户端地址落在这些地址段里的请求。逗号分隔的 CIDR，留空表示不限制。
	 */
	private String allowIps = "";

	/**
	 * 不对外开放的路径前缀，逗号分隔，例如 {@code /actuator,/v3/api-docs}。这些路径只有
	 * {@link #restrictedAllowIps} 里的地址能访问。留空表示没有这样的路径。
	 */
	private String restrictedPaths = "";

	/**
	 * 能访问 {@link #restrictedPaths} 的地址段，逗号分隔的 CIDR。留空表示谁都不能经网关访问那些路径。
	 */
	private String restrictedAllowIps = "";

	private final AccessLog accessLog = new AccessLog();

	private final DynamicRoutes dynamicRoutes = new DynamicRoutes();

	private final RateLimit rateLimit = new RateLimit();

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

	public String getDenyIps() {
		return denyIps;
	}

	public void setDenyIps(String denyIps) {
		this.denyIps = denyIps == null ? "" : denyIps;
	}

	public String getAllowIps() {
		return allowIps;
	}

	public void setAllowIps(String allowIps) {
		this.allowIps = allowIps == null ? "" : allowIps;
	}

	public String getRestrictedPaths() {
		return restrictedPaths;
	}

	public void setRestrictedPaths(String restrictedPaths) {
		this.restrictedPaths = restrictedPaths == null ? "" : restrictedPaths;
	}

	public String getRestrictedAllowIps() {
		return restrictedAllowIps;
	}

	public void setRestrictedAllowIps(String restrictedAllowIps) {
		this.restrictedAllowIps = restrictedAllowIps == null ? "" : restrictedAllowIps;
	}

	public AccessLog getAccessLog() {
		return accessLog;
	}

	public DynamicRoutes getDynamicRoutes() {
		return dynamicRoutes;
	}

	public RateLimit getRateLimit() {
		return rateLimit;
	}

	/**
	 * 限流（前缀 {@code mugsun.gateway.rate-limit}）：按客户端地址数请求，超过限额的不转发，返回 429。三项都留空就不限流。
	 */
	public static class RateLimit {

		/**
		 * 默认限额，写成 {@code 次数/时长}，时长是数字加 s、m、h，例如 {@code 600/60s}。
		 * 没有单独定限额的路径都用它；留空表示这些路径不限流。
		 */
		private String limit = "";

		/**
		 * 给个别路径单独定的限额，逗号分隔，每条写成 {@code 路径前缀=次数/时长}，例如 {@code /auth/login=30/60s}；
		 * 右边写 {@code unlimited} 表示这个前缀下不限流。一个请求只算一条：前缀最长的那条。
		 */
		private String pathLimits = "";

		/**
		 * 不限流的客户端地址段，逗号分隔的 CIDR。
		 */
		private String exemptIps = "";

		public String getLimit() {
			return limit;
		}

		public void setLimit(String limit) {
			this.limit = limit == null ? "" : limit;
		}

		public String getPathLimits() {
			return pathLimits;
		}

		public void setPathLimits(String pathLimits) {
			this.pathLimits = pathLimits == null ? "" : pathLimits;
		}

		public String getExemptIps() {
			return exemptIps;
		}

		public void setExemptIps(String exemptIps) {
			this.exemptIps = exemptIps == null ? "" : exemptIps;
		}
	}

	/**
	 * 动态路由（前缀 {@code mugsun.gateway.dynamic-routes}）：从 Nacos 的一份配置里读路由，改了不用重启网关。
	 */
	public static class DynamicRoutes {

		/** 是否启用。默认不启用：路由只有 application.yml 里写的那些 */
		private boolean enabled = false;

		/** 放路由的那份配置的 Data ID */
		private String dataId = "mugsun-gateway-routes.yaml";

		/** 那份配置所在的分组；留空表示和服务注册用同一个分组 */
		private String group = "";

		public boolean isEnabled() {
			return enabled;
		}

		/**
		 * 参数用 Boolean 不用 boolean：环境变量在、值是空的时候（编排文件里没配的变量常是这样），空字符串转不成 boolean，
		 * 网关会启动不了；用 Boolean 时这一项保持默认值，和没有这个变量一样
		 */
		public void setEnabled(Boolean enabled) {
			this.enabled = enabled != null && enabled;
		}

		public String getDataId() {
			return dataId;
		}

		public void setDataId(String dataId) {
			this.dataId = dataId == null ? "" : dataId;
		}

		public String getGroup() {
			return group;
		}

		public void setGroup(String group) {
			this.group = group == null ? "" : group;
		}
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

		private static final long DEFAULT_SLOW_MILLIS = 3000;

		/** 记哪些请求 */
		private Mode mode = Mode.ALL;

		/**
		 * 从收到请求到开始回应超过这么多毫秒算慢请求，这一行按警告级别记；0 表示不按耗时区分。
		 * 不看整个请求的时长：下载大文件、流式输出、WebSocket 连接本来就会持续很久。
		 */
		private long slowMillis = DEFAULT_SLOW_MILLIS;

		public Mode getMode() {
			return mode;
		}

		public void setMode(Mode mode) {
			this.mode = mode == null ? Mode.ALL : mode;
		}

		public long getSlowMillis() {
			return slowMillis;
		}

		/** 参数用 Long 不用 long，原因和 {@link DynamicRoutes#setEnabled} 一样：变量在、值是空的时候保持默认值 */
		public void setSlowMillis(Long slowMillis) {
			this.slowMillis = slowMillis == null ? DEFAULT_SLOW_MILLIS : slowMillis;
		}
	}
}
