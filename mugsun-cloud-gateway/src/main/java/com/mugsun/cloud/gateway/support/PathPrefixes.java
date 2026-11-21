package com.mugsun.cloud.gateway.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 一组路径前缀，用来判断一个请求路径是不是落在其中某个前缀下面。
 *
 * <p>和 {@link PathGuard} 一样按最宽的理解判断：网关和后面的服务对同一个路径的理解可能不一样（要不要解码、
 * 要不要合并连续的斜杠、要不要去掉分号后面的参数、分不分大小写），只要有一种理解会让它落在前缀下面，就算落在下面。
 * 带上级目录段（{@code ..}）的路径说不清最后落在哪，也算。
 *
 * <p>前缀按整段比较：{@code /actuator} 管 {@code /actuator} 和 {@code /actuator/health}，不管 {@code /actuatorx}。
 */
public final class PathPrefixes {

	private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._~-]+");

	/** 客户端可能把路径编码了不止一层，最多逐层解开这么多次。和 {@link PathGuard} 用的次数一样 */
	private static final int MAX_DECODE_ROUNDS = 3;

	private final List<List<String>> prefixes;

	private PathPrefixes(List<List<String>> prefixes) {
		this.prefixes = prefixes;
	}

	/**
	 * @param text     路径前缀，逗号分隔，例如 {@code /actuator,/v3/api-docs}；空表示一个都没有
	 * @param property 配置项的名字，写错时用在报错信息里
	 * @throws IllegalArgumentException 有前缀写得不对。不能忽略：忽略掉的那个前缀就没人拦了
	 */
	public static PathPrefixes parse(String text, String property) {
		List<List<String>> prefixes = new ArrayList<>();
		if (text != null) {
			for (String item : text.split("[,;\\s]+")) {
				if (!item.isEmpty()) {
					prefixes.add(segmentsOfPrefix(item, property));
				}
			}
		}
		return new PathPrefixes(List.copyOf(prefixes));
	}

	private static List<String> segmentsOfPrefix(String item, String property) {
		List<String> segments = new ArrayList<>();
		boolean valid = item.startsWith("/");
		for (String segment : item.split("/")) {
			if (segment.isEmpty()) {
				continue;
			}
			if (!SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
				valid = false;
			}
			segments.add(segment.toLowerCase(Locale.ROOT));
		}
		if (!valid || segments.isEmpty()) {
			throw new IllegalArgumentException(property + " 里的路径前缀不合法：" + item
				+ "。要以 / 开头，每一段只能由字母、数字和 . _ ~ - 组成，例如 /actuator；不能只写一个 /");
		}
		return List.copyOf(segments);
	}

	public boolean isEmpty() {
		return prefixes.isEmpty();
	}

	public int size() {
		return prefixes.size();
	}

	/**
	 * @param rawPath 请求路径的原始写法（未解码，不含查询串）
	 */
	public boolean contains(String rawPath) {
		if (prefixes.isEmpty() || rawPath == null || rawPath.isEmpty()) {
			return false;
		}
		String current = rawPath;
		for (int round = 0; ; round++) {
			if (containsLiteral(current)) {
				return true;
			}
			if (round == MAX_DECODE_ROUNDS || current.indexOf('%') < 0) {
				return false;
			}
			String decoded = decodeOnce(current);
			if (decoded.equals(current)) {
				return false;
			}
			current = decoded;
		}
	}

	private boolean containsLiteral(String path) {
		List<String> segments = new ArrayList<>();
		int start = 0;
		int length = path.length();
		for (int i = 0; i <= length; i++) {
			char c = i < length ? path.charAt(i) : '/';
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
				return true;
			}
			segments.add(segment.toLowerCase(Locale.ROOT));
		}
		for (List<String> prefix : prefixes) {
			if (segments.size() >= prefix.size() && segments.subList(0, prefix.size()).equals(prefix)) {
				return true;
			}
		}
		return false;
	}

	/** 解开一层百分号编码；不成对的百分号原样保留。规则和 {@link PathGuard} 用的那份一样，两处要保持一致。 */
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
