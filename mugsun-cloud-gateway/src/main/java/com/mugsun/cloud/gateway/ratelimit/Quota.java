package com.mugsun.cloud.gateway.ratelimit;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一条限额，写成 {@code 次数/时长}，例如 {@code 600/60s}。
 *
 * <p>按令牌桶算：桶里最多装「次数」个令牌，每个请求用掉一个，桶空了就拒绝；用掉的令牌匀速补回来，补满一整桶正好用一个「时长」。
 * 所以一直有请求的时候，每个时长里过「次数」个；歇够一个时长以后桶是满的，可以一口气连发「次数」个，
 * 这时候头一个时长里最多能过两倍（先连发一整桶，再加上这段时间补回来的）。
 *
 * <p>同样的速度，时长写得越长，允许连发的个数越多：{@code 10/1s} 最多连发 10 个，{@code 600/60s} 最多连发 600 个。
 *
 * @param requests 次数
 * @param per      时长
 */
public record Quota(long requests, Duration per) {

	/** 次数的上限 */
	public static final long MAX_REQUESTS = 1_000_000;

	/** 时长的上限。计数放在内存里，网关重启就清零，每个桶还要在内存里留够一个时长，所以不收更长的 */
	public static final Duration MAX_PER = Duration.ofHours(1);

	private static final Pattern FORMAT = Pattern.compile("(\\d{1,9})/(\\d{1,6})([smh])");

	/**
	 * @param text     限额的写法，例如 {@code 600/60s}、{@code 30/1m}
	 * @param property 配置项的名字，写错时用在报错信息里
	 * @throws IllegalArgumentException 写得不对。不能忽略：忽略掉的那条限额就等于没有限流
	 */
	public static Quota parse(String text, String property) {
		Matcher matcher = FORMAT.matcher(text == null ? "" : text.strip());
		if (matcher.matches()) {
			long requests = Long.parseLong(matcher.group(1));
			long amount = Long.parseLong(matcher.group(2));
			Duration per = switch (matcher.group(3)) {
				case "s" -> Duration.ofSeconds(amount);
				case "m" -> Duration.ofMinutes(amount);
				default -> Duration.ofHours(amount);
			};
			if (requests >= 1 && requests <= MAX_REQUESTS && !per.isZero() && per.compareTo(MAX_PER) <= 0) {
				return new Quota(requests, per);
			}
		}
		throw new IllegalArgumentException(property + " 里的限额不合法：" + text
			+ "。要写成 次数/时长，时长是数字加 s、m、h（秒、分、时），例如 600/60s 或 30/1m；"
			+ "次数从 1 到 " + MAX_REQUESTS + "，时长从 1 秒到 1 小时");
	}

	/** 统一换算成秒的写法，例如 {@code 30/60s}，用在日志里 */
	@Override
	public String toString() {
		return requests + "/" + per.toSeconds() + "s";
	}
}
