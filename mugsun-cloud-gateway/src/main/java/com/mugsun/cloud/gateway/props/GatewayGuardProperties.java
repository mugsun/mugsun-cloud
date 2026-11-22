package com.mugsun.cloud.gateway.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关自身的配置（前缀 {@code mugsun.gateway}）。
 *
 * <p>配置项都直接写在这一层，不分组（不要加嵌套的对象）；环境变量的名字就是配置项的名字换成大写加下划线，
 * {@code mugsun.gateway.rate-limit-paths} 对应 {@code MUGSUN_GATEWAY_RATE_LIMIT_PATHS}。
 * 原因：Spring Boot 会按名字把环境变量对到配置项上。分了组以后，和组同名的变量会被拿去给整组赋值：
 * 有过一组叫 {@code access-log}，变量 {@code MUGSUN_GATEWAY_ACCESS_LOG} 有值时靠 Spring Boot 的兜底处理碰巧能用，
 * 变量在、值是空的时候网关启动不了。只有一层就没有组，也就没有和组同名的变量。
 *
 * <p>变量在、值是空的（编排文件把没配的变量传进容器时就是这样）等于没配，每一项都按默认值。
 * 所以不是文字的那几项，setter 的参数用 Long、Boolean 这样的类型，不用 long、boolean：
 * 空字符串转不成 long，用 long 的话网关会启动不了；用 Long 时 Spring Boot 不调 setter，这一项保持默认值。
 */
@ConfigurationProperties(prefix = "mugsun.gateway")
public class GatewayGuardProperties {

	/** 访问日志记哪些请求 */
	public enum AccessLogMode {
		/** 每个请求都记 */
		ALL,
		/** 只记有问题的：5xx、转发出错、慢请求 */
		PROBLEMS,
		/** 不记。不叫 off：YAML 会把没加引号的 off 当成布尔值 */
		NONE
	}

	private static final long DEFAULT_SLOW_MILLIS = 3000;

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

	/**
	 * 限流的默认限额，写成 {@code 次数/时长}，时长是数字加 s、m、h，例如 {@code 600/60s}。按客户端地址数请求，
	 * 超过限额的不转发，返回 429。没有单独定限额的路径都用它；留空表示这些路径不限流。
	 * 这一项和下面两项都留空就不限流。
	 */
	private String rateLimit = "";

	/**
	 * 给个别路径单独定的限额，逗号分隔，每条写成 {@code 路径前缀=次数/时长}，例如 {@code /auth/login=30/60s}；
	 * 右边写 {@code unlimited} 表示这个前缀下不限流。一个请求只算一条：前缀最长的那条。
	 */
	private String rateLimitPaths = "";

	/**
	 * 不限流的客户端地址段，逗号分隔的 CIDR。
	 */
	private String rateLimitExemptIps = "";

	/**
	 * 访问日志记哪些请求。每个请求结束时记一行，日志名是 ACCESS。
	 */
	private AccessLogMode accessLog = AccessLogMode.ALL;

	/**
	 * 从收到请求到开始回应超过这么多毫秒算慢请求，访问日志里这一行按警告级别记；0 表示不按耗时区分。
	 * 不看整个请求的时长：下载大文件、流式输出、WebSocket 连接本来就会持续很久。
	 */
	private long slowMillis = DEFAULT_SLOW_MILLIS;

	/**
	 * 是否启用动态路由：从 Nacos 的一份配置里读路由，改了不用重启网关。默认不启用：路由只有 application.yml 里写的那些。
	 */
	private boolean dynamicRoutes = false;

	/** 放动态路由的那份配置的 Data ID */
	private String dynamicRoutesDataId = "mugsun-gateway-routes.yaml";

	/** 那份配置所在的分组；留空表示和服务注册用同一个分组 */
	private String dynamicRoutesGroup = "";

	/**
	 * 是否启用灰度规则：从 Nacos 的一份配置里读「哪些请求走灰度」，改了不用重启网关。默认不启用：没有任何请求走灰度。
	 * 不管启不启用，带着灰度标记启动的实例都收不到没有标记的请求。
	 */
	private boolean lanes = false;

	/** 放灰度规则的那份配置的 Data ID */
	private String lanesDataId = "mugsun-gateway-lanes.yaml";

	/** 那份配置所在的分组；留空表示和服务注册用同一个分组 */
	private String lanesGroup = "";

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

	public String getRateLimit() {
		return rateLimit;
	}

	public void setRateLimit(String rateLimit) {
		this.rateLimit = rateLimit == null ? "" : rateLimit;
	}

	public String getRateLimitPaths() {
		return rateLimitPaths;
	}

	public void setRateLimitPaths(String rateLimitPaths) {
		this.rateLimitPaths = rateLimitPaths == null ? "" : rateLimitPaths;
	}

	public String getRateLimitExemptIps() {
		return rateLimitExemptIps;
	}

	public void setRateLimitExemptIps(String rateLimitExemptIps) {
		this.rateLimitExemptIps = rateLimitExemptIps == null ? "" : rateLimitExemptIps;
	}

	public AccessLogMode getAccessLog() {
		return accessLog;
	}

	public void setAccessLog(AccessLogMode accessLog) {
		this.accessLog = accessLog == null ? AccessLogMode.ALL : accessLog;
	}

	public long getSlowMillis() {
		return slowMillis;
	}

	public void setSlowMillis(Long slowMillis) {
		this.slowMillis = slowMillis == null ? DEFAULT_SLOW_MILLIS : slowMillis;
	}

	public boolean isDynamicRoutes() {
		return dynamicRoutes;
	}

	public void setDynamicRoutes(Boolean dynamicRoutes) {
		this.dynamicRoutes = dynamicRoutes != null && dynamicRoutes;
	}

	public String getDynamicRoutesDataId() {
		return dynamicRoutesDataId;
	}

	public void setDynamicRoutesDataId(String dynamicRoutesDataId) {
		this.dynamicRoutesDataId = dynamicRoutesDataId == null ? "" : dynamicRoutesDataId;
	}

	public String getDynamicRoutesGroup() {
		return dynamicRoutesGroup;
	}

	public void setDynamicRoutesGroup(String dynamicRoutesGroup) {
		this.dynamicRoutesGroup = dynamicRoutesGroup == null ? "" : dynamicRoutesGroup;
	}

	public boolean isLanes() {
		return lanes;
	}

	public void setLanes(Boolean lanes) {
		this.lanes = lanes != null && lanes;
	}

	public String getLanesDataId() {
		return lanesDataId;
	}

	public void setLanesDataId(String lanesDataId) {
		this.lanesDataId = lanesDataId == null ? "" : lanesDataId;
	}

	public String getLanesGroup() {
		return lanesGroup;
	}

	public void setLanesGroup(String lanesGroup) {
		this.lanesGroup = lanesGroup == null ? "" : lanesGroup;
	}
}
