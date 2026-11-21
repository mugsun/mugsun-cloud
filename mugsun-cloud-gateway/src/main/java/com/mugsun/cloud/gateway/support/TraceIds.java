package com.mugsun.cloud.gateway.support;

import org.springframework.web.server.ServerWebExchange;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 请求编号：一次请求在网关和后面各服务的日志里用同一个编号，排查问题时拿它把几边的日志对上。
 *
 * <p>编号放在 {@code X-Trace-Id} 请求头里传给服务。服务那边（mugsun-boot 的 {@code TraceIdFilter}）收到合法的编号就沿用，
 * 写进自己的每一行日志，并在响应头里原样带回。
 */
public final class TraceIds {

	public static final String HEADER = "X-Trace-Id";

	/** 编号在请求上下文里的属性名 */
	public static final String ATTRIBUTE = TraceIds.class.getName() + ".traceId";

	private static final int MAX_LENGTH = 64;

	private TraceIds() {
	}

	/**
	 * 客户端带来的编号能不能沿用：只能是字母、数字、连字符，1 到 64 位。规则和服务那边一致，
	 * 别的写法一律不认，免得有人借它往日志里塞换行或别的内容。
	 */
	public static boolean acceptable(String value) {
		if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-';
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	/** 生成一个新编号：32 位十六进制。只用来对日志，不当凭据用，所以不需要密码学强度的随机数 */
	public static String generate() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		HexFormat hex = HexFormat.of();
		return hex.toHexDigits(random.nextLong()) + hex.toHexDigits(random.nextLong());
	}

	/** 这次请求的编号；还没分配时返回 {@code -} */
	public static String of(ServerWebExchange exchange) {
		Object value = exchange.getAttribute(ATTRIBUTE);
		return value instanceof String text ? text : "-";
	}
}
