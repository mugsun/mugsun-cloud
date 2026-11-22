package com.mugsun.cloud.config.logging;

import java.util.regex.Pattern;

/**
 * 日志里不该留下的凭证：每一行日志写出去之前先过 {@link #mask}，认出来的换成 {@code ***}。
 * 认的是形状，不管这一行是谁打的：
 * <ul>
 * <li>Nacos 的访问令牌。发给 Nacos 的请求失败时，Nacos 的客户端把请求连同请求头整个打进 ERROR 日志
 * （{@code Send request fail, request = InstanceRequest{headers={accessToken=...}}）。令牌没过期时，
 * 拿着它就能以接入账号的身份访问 Nacos；</li>
 * <li>JWT 形状的一串（点号隔开的三段，前两段都以 {@code eyJ} 开头）。Nacos 的访问令牌就是这种，换一种写法打出来也认得；</li>
 * <li>地址里的 {@code token=}。站内信的 WebSocket 连接把登录令牌放在查询串里，框架出错时会把完整的地址记下来；</li>
 * <li>{@code password=}。Nacos 的客户端启动时把接入账号的口令打一遍（{@code Nacos client key init properties}），
 * 只盖住中间，头尾各露两个字符，口令多长也看得出来。</li>
 * </ul>
 * 这是兜底，不是可以往日志里打凭证的理由：自己写的代码仍然不记口令、密钥、令牌和签名。
 * 加新的形状时在 {@code LogSecretsTest} 里补上例子：既要有换掉的，也要有长得像但不该动的。
 */
public final class LogSecrets {

	/** 换上去的占位。自检拿「{@code accessToken=} 后面是不是它」判断令牌有没有漏出去，改它要连 {@code smoke.sh} 一起改 */
	public static final String MASK = "***";

	/** Nacos 的客户端打出来的请求头：{@code headers={accessToken=..., app=unknown}}，值到逗号、右花括号或空白为止 */
	private static final Pattern NACOS_ACCESS_TOKEN = Pattern.compile("(accessToken=)[^,}\\s]+");

	/** 地址里的登录令牌：{@code /ws/message?token=...}，值到下一个参数、空白或引号为止 */
	private static final Pattern TOKEN_IN_QUERY = Pattern.compile("([?&]token=)[^&\\s\"'<>]+");

	/**
	 * 口令：{@code password=...}，前面可以还有别的字（{@code NACOS_PASSWORD=}、{@code oldPassword=}）。值到空白或 {@code &} 为止：
	 * 口令里可以有逗号和括号，宁可多盖掉后面一个标点，也不把口令的后半截留在日志里
	 */
	private static final Pattern PASSWORD = Pattern.compile("((?:[Pp]assword|PASSWORD)=)[^\\s&]+");

	/**
	 * JWT：三段 base64url。头和内容都是 JSON 对象，编码以后都以 {@code eyJ} 开头。两段都要求这样开头，
	 * 是为了不误伤类名：{@code MoneyJournalService.MoneyJournalEntry.toString} 里也有 {@code eyJ} 和两个点号
	 */
	private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

	private LogSecrets() {
	}

	/**
	 * 把一行日志（连同后面的异常堆栈）里认得出的凭证换成 {@link #MASK}。绝大多数日志里没有这些形状，
	 * 先用几次子串查找排除掉，不进正则。
	 */
	public static String mask(String text) {
		if (text == null || !mayHoldSecret(text)) {
			return text;
		}
		String masked = NACOS_ACCESS_TOKEN.matcher(text).replaceAll("$1" + MASK);
		masked = TOKEN_IN_QUERY.matcher(masked).replaceAll("$1" + MASK);
		masked = PASSWORD.matcher(masked).replaceAll("$1" + MASK);
		return JWT.matcher(masked).replaceAll(MASK);
	}

	/** 上面每条规则都离不开这几个子串中的一个：加规则时这里要跟着加，不然新规则永远轮不到 */
	private static boolean mayHoldSecret(String text) {
		return text.contains("oken=") || text.contains("assword=") || text.contains("PASSWORD=")
				|| text.contains("eyJ");
	}
}
