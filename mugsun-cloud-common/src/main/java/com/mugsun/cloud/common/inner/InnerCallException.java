package com.mugsun.cloud.common.inner;

/**
 * 内部调用没有成功。对方明确返回的业务失败不在此列：那种情况 {@link InnerClient} 直接抛 {@code ServiceException}。
 */
public class InnerCallException extends RuntimeException {

	public enum Kind {

		/** 没有配置密钥，内部调用停用 */
		NOT_CONFIGURED,

		/** 注册中心里没有对方的实例 */
		NO_INSTANCE,

		/** 对方没让过：两边的密钥不一致，或者机器时间差得太多 */
		REJECTED,

		/** 对方正忙：那个接口同时在处理的请求到了上限，这次没有执行 */
		BUSY,

		/** 熔断中：前几次调用接连失败，这段时间先不调它，这次没有发请求 */
		BREAKER_OPEN,

		/** 其它：连不上、超时、对方出错、响应没有通过验签 */
		FAILED
	}

	private final Kind kind;

	public InnerCallException(Kind kind, String message) {
		super(message);
		this.kind = kind;
	}

	public InnerCallException(Kind kind, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	/** 对方服务算不算「没有接通」（没配密钥，或者没有实例）：这种情况按「那个模块没装」处理，而不是按故障处理 */
	public boolean absent() {
		return kind == Kind.NOT_CONFIGURED || kind == Kind.NO_INSTANCE;
	}
}
