package com.mugsun.cloud.gateway.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 判断一个请求路径能不能转发：服务之间互相调用的内部接口（默认 /inner 开头）不对外，写法可疑的路径直接拒绝。
 *
 * <p>网关和后面的服务对同一个路径的理解可能不一样（要不要解码、要不要合并连续的斜杠、要不要去掉分号后面的参数），
 * 所以这里按最宽的理解来判断：只要有一种理解会把它当成内部接口，就按内部接口处理。
 */
public final class PathGuard {

	public enum Verdict {
		/** 可以转发 */
		PASS,
		/** 内部接口，不对外 */
		INNER,
		/** 写法不合法：带 .. 段或控制字符 */
		MALFORMED
	}

	/** 客户端可能把路径编码了不止一层，最多逐层解开这么多次 */
	private static final int MAX_DECODE_ROUNDS = 3;

	private final String innerSegment;

	/**
	 * @param innerPath 内部接口的路径前缀，只能是一段，例如 {@code /inner}
	 */
	public PathGuard(String innerPath) {
		String segment = innerPath == null ? "" : innerPath.trim().replaceAll("^/+|/+$", "");
		if (!segment.matches("[A-Za-z0-9_-]+")) {
			throw new IllegalArgumentException(
				"mugsun.gateway.inner-path 只能是一段由字母、数字、下划线、连字符组成的路径，例如 /inner，当前值：" + innerPath);
		}
		this.innerSegment = segment;
	}

	/**
	 * @param rawPath 请求路径的原始写法（未解码，不含查询串）
	 */
	public Verdict inspect(String rawPath) {
		if (rawPath == null || rawPath.isEmpty()) {
			return Verdict.PASS;
		}
		String current = rawPath;
		for (int round = 0; ; round++) {
			Verdict verdict = inspectLiteral(current);
			if (verdict != Verdict.PASS || round == MAX_DECODE_ROUNDS || current.indexOf('%') < 0) {
				return verdict;
			}
			String decoded = decodeOnce(current);
			if (decoded.equals(current)) {
				return Verdict.PASS;
			}
			current = decoded;
		}
	}

	private Verdict inspectLiteral(String path) {
		boolean first = true;
		int start = 0;
		int length = path.length();
		for (int i = 0; i <= length; i++) {
			char c = i < length ? path.charAt(i) : '/';
			if (c < 0x20 || c == 0x7F) {
				return Verdict.MALFORMED;
			}
			if (c != '/' && c != '\\') {
				continue;
			}
			String segment = path.substring(start, i);
			start = i + 1;
			int semicolon = segment.indexOf(';');
			if (semicolon >= 0) {
				segment = segment.substring(0, semicolon);
			}
			segment = segment.strip();
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..")) {
				return Verdict.MALFORMED;
			}
			if (first && segment.equalsIgnoreCase(innerSegment)) {
				return Verdict.INNER;
			}
			first = false;
		}
		return Verdict.PASS;
	}

	/** 解开一层百分号编码；不成对的百分号原样保留。 */
	private static String decodeOnce(String text) {
		byte[] source = text.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream out = new ByteArrayOutputStream(source.length);
		for (int i = 0; i < source.length; i++) {
			int b = source[i];
			if (b == '%' && i + 2 < source.length && hex(source[i + 1]) >= 0 && hex(source[i + 2]) >= 0) {
				out.write(hex(source[i + 1]) << 4 | hex(source[i + 2]));
				i += 2;
			} else {
				out.write(b);
			}
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static int hex(byte b) {
		if (b >= '0' && b <= '9') {
			return b - '0';
		}
		if (b >= 'a' && b <= 'f') {
			return b - 'a' + 10;
		}
		if (b >= 'A' && b <= 'F') {
			return b - 'A' + 10;
		}
		return -1;
	}
}
