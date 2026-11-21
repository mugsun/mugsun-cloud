package com.mugsun.cloud.common.inner;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 服务之间互相调用（{@code /inner} 开头的接口）的配置，前缀 {@code mugsun.cloud.inner}。
 *
 * <p>{@link #secret} 是所有业务服务共用的一把密钥，没有默认值。不配时内部调用整体停用：本服务不接受任何 {@code /inner} 请求，
 * 也不向别的服务发起调用，依赖内部调用的功能按「对方服务没有部署」处理。配了但不足 {@value #MIN_SECRET_LENGTH} 个字符时拒绝启动，
 * 免得用一把猜得出来的密钥上线。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.cloud.inner")
public class InnerProperties implements InitializingBean {

	/** 密钥最少要这么多个字符 */
	public static final int MIN_SECRET_LENGTH = 32;

	/** 时间差上限最多能配到这么大：再大，截获的请求能重放的时间窗口就太长了 */
	static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(10);

	/** 共享密钥。各服务必须一致；首尾的空白会去掉 */
	private String secret = "";

	/** 调用方和被调用方的机器时间最多差多少，超过就拒绝 */
	private Duration clockSkew = Duration.ofSeconds(60);

	/** 连接超时 */
	private Duration connectTimeout = Duration.ofSeconds(2);

	/** 等对方返回的时间上限 */
	private Duration readTimeout = Duration.ofSeconds(10);

	/** 对方要去调大模型的那几个接口，等返回的时间上限 */
	private Duration longReadTimeout = Duration.ofSeconds(120);

	/** 接受的请求体上限 */
	private DataSize maxRequestSize = DataSize.ofMegabytes(1);

	/** 接受的响应体上限 */
	private DataSize maxResponseSize = DataSize.ofMegabytes(8);

	/**
	 * 本服务的每个内部接口同时最多处理几个请求。满了的时候新来的先等 {@link #maxWait}，还轮不到就回「忙」。
	 * 默认 5，是数据库连接池（默认 10 个连接）的一半：内部接口的查询卡住时，本服务自己的接口还有连接可用
	 */
	private int maxConcurrent = 5;

	/** 内部接口处理满了的时候，新来的请求最多等多久；0 是不等 */
	private Duration maxWait = Duration.ofSeconds(2);

	/** 替同一个租户调同一个实例的同一个接口接连失败几次就熔断（先不调它，直接报失败）；0 是不熔断 */
	private int breakerFailures = 3;

	/** 熔断以后停多久再放一个请求过去试 */
	private Duration breakerPause = Duration.ofSeconds(10);

	@Override
	public void afterPropertiesSet() {
		validate();
	}

	void validate() {
		secret = secret == null ? "" : secret.strip();
		if (!secret.isEmpty() && secret.length() < MIN_SECRET_LENGTH) {
			throw new IllegalStateException("mugsun.cloud.inner.secret（环境变量 MUGSUN_INNER_SECRET）至少要 "
				+ MIN_SECRET_LENGTH + " 个字符，现在只有 " + secret.length() + " 个。可以用 openssl rand -hex 32 生成一个");
		}
		requirePositive("clock-skew", clockSkew);
		if (clockSkew.compareTo(MAX_CLOCK_SKEW) > 0) {
			throw new IllegalStateException("mugsun.cloud.inner.clock-skew 最大 " + MAX_CLOCK_SKEW.toMinutes() + " 分钟");
		}
		requirePositive("connect-timeout", connectTimeout);
		requirePositive("read-timeout", readTimeout);
		requirePositive("long-read-timeout", longReadTimeout);
		requireSize("max-request-size", maxRequestSize);
		requireSize("max-response-size", maxResponseSize);
		if (maxConcurrent < 1) {
			throw new IllegalStateException("mugsun.cloud.inner.max-concurrent 至少是 1，现在是 " + maxConcurrent);
		}
		if (maxWait == null || maxWait.isNegative()) {
			throw new IllegalStateException("mugsun.cloud.inner.max-wait 不能是负数");
		}
		if (breakerFailures < 0) {
			throw new IllegalStateException("mugsun.cloud.inner.breaker-failures 不能是负数（不想熔断就配 0），现在是 "
				+ breakerFailures);
		}
		requirePositive("breaker-pause", breakerPause);
	}

	private static void requirePositive(String name, Duration value) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException("mugsun.cloud.inner." + name + " 必须大于 0");
		}
	}

	private static void requireSize(String name, DataSize value) {
		if (value == null || value.toBytes() <= 0 || value.toBytes() > Integer.MAX_VALUE - 8) {
			throw new IllegalStateException("mugsun.cloud.inner." + name + " 必须大于 0，且不超过 2GB");
		}
	}

	/** 配了密钥才启用内部调用（方法名不用 is/get 开头，免得被当成一个配置项） */
	public boolean configured() {
		return !secret.isEmpty();
	}

	byte[] key() {
		return secret.getBytes(StandardCharsets.UTF_8);
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}

	public Duration getClockSkew() {
		return clockSkew;
	}

	public void setClockSkew(Duration clockSkew) {
		this.clockSkew = clockSkew;
	}

	public Duration getConnectTimeout() {
		return connectTimeout;
	}

	public void setConnectTimeout(Duration connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	public Duration getReadTimeout() {
		return readTimeout;
	}

	public void setReadTimeout(Duration readTimeout) {
		this.readTimeout = readTimeout;
	}

	public Duration getLongReadTimeout() {
		return longReadTimeout;
	}

	public void setLongReadTimeout(Duration longReadTimeout) {
		this.longReadTimeout = longReadTimeout;
	}

	public DataSize getMaxRequestSize() {
		return maxRequestSize;
	}

	public void setMaxRequestSize(DataSize maxRequestSize) {
		this.maxRequestSize = maxRequestSize;
	}

	public DataSize getMaxResponseSize() {
		return maxResponseSize;
	}

	public void setMaxResponseSize(DataSize maxResponseSize) {
		this.maxResponseSize = maxResponseSize;
	}

	public int getMaxConcurrent() {
		return maxConcurrent;
	}

	public void setMaxConcurrent(int maxConcurrent) {
		this.maxConcurrent = maxConcurrent;
	}

	public Duration getMaxWait() {
		return maxWait;
	}

	public void setMaxWait(Duration maxWait) {
		this.maxWait = maxWait;
	}

	public int getBreakerFailures() {
		return breakerFailures;
	}

	public void setBreakerFailures(int breakerFailures) {
		this.breakerFailures = breakerFailures;
	}

	public Duration getBreakerPause() {
		return breakerPause;
	}

	public void setBreakerPause(Duration breakerPause) {
		this.breakerPause = breakerPause;
	}
}
