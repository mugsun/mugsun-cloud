package com.mugsun.cloud.gateway.ratelimit;

import com.mugsun.cloud.gateway.support.PathPrefixes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 限流的规则：一条默认的限额，加上给个别路径前缀单独定的限额。
 *
 * <p>一个请求只算在一条规则上：路径落在哪些前缀下面，取前缀最长的那条；一条都不沾的用默认的；默认的也没配就不限流。
 * 这样单独定的限额既可以比默认的严（登录），也可以比默认的松（地图瓦片），还可以写 {@value #UNLIMITED} 表示不限流。
 *
 * <p>路径落不落在前缀下面，和「不对外开放的路径」用同一个判断（{@link PathPrefixes}）：按整段比较、不分大小写，
 * 把路径编码、多写斜杠、加分号参数都算落在下面，换个写法躲不开为某个路径单独定的限额。
 */
public final class RateLimitRules {

	/** 写在路径前缀后面、表示这个前缀下不限流的词 */
	public static final String UNLIMITED = "unlimited";

	private static final String DEFAULT_NAME = "默认";

	/**
	 * 一条规则。
	 *
	 * @param index 序号，同一份规则里不重复，用来把各条规则的计数分开
	 * @param name  写进日志的名字：默认规则叫「默认」，路径规则用它的路径前缀
	 * @param quota 限额；null 表示不限流
	 */
	public record Rule(int index, String name, Quota quota) {

		public boolean unlimited() {
			return quota == null;
		}

		@Override
		public String toString() {
			return name + "=" + (quota == null ? UNLIMITED : quota);
		}
	}

	private record PathRule(PathPrefixes prefix, int segments, Rule rule) {
	}

	private final Rule defaultRule;

	/** 前缀长的排在前面；一样长的按写的先后 */
	private final List<PathRule> pathRules;

	private RateLimitRules(Rule defaultRule, List<PathRule> pathRules) {
		this.defaultRule = defaultRule;
		this.pathRules = pathRules;
	}

	/**
	 * @param defaultLimit    默认限额，写成 {@code 次数/时长}；空表示没有默认限额
	 * @param defaultProperty 默认限额那个配置项的名字，写错时用在报错信息里
	 * @param pathLimits      单独定的限额，逗号分隔，每条写成 {@code 路径前缀=次数/时长} 或 {@code 路径前缀=unlimited}；空表示没有
	 * @param pathProperty    单独定限额那个配置项的名字
	 * @throws IllegalArgumentException 有一条写得不对，或者同一个前缀写了两次
	 */
	public static RateLimitRules parse(String defaultLimit, String defaultProperty, String pathLimits,
			String pathProperty) {
		Rule defaultRule = defaultLimit == null || defaultLimit.isBlank()
			? null
			: new Rule(0, DEFAULT_NAME, Quota.parse(defaultLimit, defaultProperty));
		List<PathRule> pathRules = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		if (pathLimits != null) {
			for (String item : pathLimits.split("[,;\\s]+")) {
				if (item.isEmpty()) {
					continue;
				}
				int equals = item.lastIndexOf('=');
				if (equals <= 0 || equals == item.length() - 1) {
					throw new IllegalArgumentException(pathProperty + " 里有一条写得不对：" + item
						+ "。每条要写成 路径前缀=次数/时长，例如 /auth/login=30/60s；不限流的写 路径前缀=" + UNLIMITED);
				}
				String prefix = item.substring(0, equals);
				String limit = item.substring(equals + 1);
				PathPrefixes parsed = PathPrefixes.parse(prefix, pathProperty);
				List<String> segments = segmentsOf(prefix);
				if (!seen.add(String.join("/", segments))) {
					throw new IllegalArgumentException(pathProperty + " 里的路径前缀 " + prefix
						+ " 写了不止一次（前缀不分大小写）。一个前缀只能定一条限额");
				}
				Quota quota = UNLIMITED.equals(limit) ? null : Quota.parse(limit, pathProperty);
				pathRules.add(new PathRule(parsed, segments.size(), new Rule(pathRules.size() + 1, "/"
					+ String.join("/", segments), quota)));
			}
		}
		// 排序是稳定的：一样长的前缀保持写的先后
		pathRules.sort(Comparator.comparingInt(PathRule::segments).reversed());
		return new RateLimitRules(defaultRule, List.copyOf(pathRules));
	}

	/** 路径前缀的各段，转成小写。前缀写得对不对已经由 {@link PathPrefixes#parse} 检查过 */
	private static List<String> segmentsOf(String prefix) {
		List<String> segments = new ArrayList<>();
		for (String segment : prefix.split("/")) {
			if (!segment.isEmpty()) {
				segments.add(segment.toLowerCase(Locale.ROOT));
			}
		}
		return segments;
	}

	/** 一条要计数的规则都没有：没有默认限额，单独定的也全是不限流 */
	public boolean isEmpty() {
		if (defaultRule != null) {
			return false;
		}
		for (PathRule pathRule : pathRules) {
			if (!pathRule.rule().unlimited()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 这个请求算在哪条规则上。
	 *
	 * @param rawPaths 这次请求涉及的各个路径的原始写法（客户端发来的、路由改写前后的），有一个落在前缀下面就算
	 * @return 规则；null 表示没有规则管这个请求
	 */
	public Rule ruleFor(Collection<String> rawPaths) {
		for (PathRule pathRule : pathRules) {
			for (String rawPath : rawPaths) {
				if (pathRule.prefix().contains(rawPath)) {
					return pathRule.rule();
				}
			}
		}
		return defaultRule;
	}

	/** 默认规则；null 表示没配 */
	public Rule defaultRule() {
		return defaultRule;
	}

	/** 单独定了限额的路径前缀有几个 */
	public int pathRuleCount() {
		return pathRules.size();
	}
}
