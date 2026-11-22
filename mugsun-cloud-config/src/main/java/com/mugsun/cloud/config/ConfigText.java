package com.mugsun.cloud.config;

import java.util.regex.Pattern;

/**
 * 从 Nacos 读来的配置（动态路由、灰度规则）出错时，写进日志的那句话里能带什么。
 * 消息是给运维看的：只说哪里不对，不带配置里的值；非带不可的名字和框架给的说明，先过这里的两个方法。
 */
public final class ConfigText {

	private static final Pattern UNSAFE_IN_NAME = Pattern.compile("[^A-Za-z0-9._\\-\\[\\]]");
	private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
	private static final int MAX_NAME = 80;
	private static final int MAX_TEXT = 200;

	private ConfigText() {
	}

	/** 配置里的名字（路由 id、断言名、配置项的名字）写进消息以前先过一遍：除了常见的字符都换成问号，过长的截断。 */
	public static String name(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "（没写）";
		}
		String cut = raw.length() > MAX_NAME ? raw.substring(0, MAX_NAME) + "..." : raw;
		return UNSAFE_IN_NAME.matcher(cut).replaceAll("?");
	}

	/** 框架给的出错说明：只取第一行，去掉控制字符，过长的截断。 */
	public static String text(String raw) {
		if (raw == null || raw.isBlank()) {
			return "（没有说明）";
		}
		String line = raw.strip();
		int newline = line.indexOf('\n');
		if (newline >= 0) {
			line = line.substring(0, newline);
		}
		line = CONTROL.matcher(line).replaceAll(" ").strip();
		return line.length() > MAX_TEXT ? line.substring(0, MAX_TEXT) + "..." : line;
	}
}
