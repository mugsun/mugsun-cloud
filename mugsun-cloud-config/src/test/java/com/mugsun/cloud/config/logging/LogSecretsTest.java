package com.mugsun.cloud.config.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志里哪些形状算凭证、换成什么样；长得像但不是凭证的一个字都不能动。
 */
class LogSecretsTest {

	/**
	 * 假的令牌，形状和 Nacos 发的访问令牌一样：三段，前两段是 JSON 对象编码出来的，所以都以 eyJ 开头。
	 * 拼出来而不是整串写在这里，免得扫密钥的工具把它当成真的。
	 */
	static final String TOKEN = String.join(".", "eyJ" + "hbGciOiJIUzI1NiJ9", "eyJ" + "zdWIiOiJtdWdzdW4ifQ",
		"c2lnbmF0dXJlLWZha2U_-x");

	/** Nacos 的客户端发请求失败时打的那一行，照真的日志抄的，令牌换成了假的 */
	static final String REQUEST_DUMP = "Send request fail, request = InstanceRequest{headers={accessToken=" + TOKEN
			+ ", app=unknown}, requestId='null'}, retryTimes = 0, errorMessage = Client not connected, current status:UNHEALTHY";

	@Test
	void nacosRequestDumpLosesOnlyTheToken() {
		assertThat(LogSecrets.mask(REQUEST_DUMP))
			.isEqualTo("Send request fail, request = InstanceRequest{headers={accessToken=***"
					+ ", app=unknown}, requestId='null'}, retryTimes = 0, errorMessage = Client not connected, current status:UNHEALTHY");
	}

	/** 请求头的顺序不固定：令牌排在最后时，后面跟的是右花括号 */
	@Test
	void tokenLastAmongTheHeaders() {
		assertThat(LogSecrets.mask("ConfigBatchListenRequest{headers={charset=UTF-8, accessToken=" + TOKEN + "}, requestId='null'}"))
			.isEqualTo("ConfigBatchListenRequest{headers={charset=UTF-8, accessToken=***}, requestId='null'}");
	}

	/** 认的是 accessToken= 这个名字，值长什么样都换 */
	@Test
	void accessTokenThatIsNotAJwt() {
		assertThat(LogSecrets.mask("headers={accessToken=0f3c9a.opaque-value, app=unknown}"))
			.isEqualTo("headers={accessToken=***, app=unknown}");
	}

	/** 换一种写法打出来的令牌：没有 accessToken= 这个名字，靠形状认 */
	@ParameterizedTest
	@ValueSource(strings = {"{\"accessToken\":\"<T>\",\"tokenTtl\":18000}", "Authorization: Bearer <T>", "token [<T>] expired",
			"redirect=%2Fws%2Fmessage%3Ftoken%3D<T>", "<T>"})
	void jwtInAnyWriting(String template) {
		assertThat(LogSecrets.mask(template.replace("<T>", TOKEN))).isEqualTo(template.replace("<T>", "***"));
	}

	/** 站内信的 WebSocket 地址把登录令牌放在查询串里。登录令牌不一定是 JWT，认的是参数名 */
	@ParameterizedTest
	@ValueSource(strings = {"/ws/message?token=<T>", "ws://127.0.0.1:8080/ws/message?token=<T>&v=2",
			"GET \"/ws/message?tenant=1&token=<T>\" failed", "<a href='/ws/message?token=<T>'>"})
	void loginTokenInAnAddress(String template) {
		for (String token : List.of("9f8e7d6c-5b4a.opaque_login.token", TOKEN)) {
			assertThat(LogSecrets.mask(template.replace("<T>", token))).isEqualTo(template.replace("<T>", "***"));
		}
	}

	/** Nacos 的客户端启动时打的那几行：口令只盖了中间，要整个盖住；地址和账号不动 */
	@Test
	void nacosClientInitProperties() {
		assertThat(LogSecrets.mask("Nacos client key init properties: \n\tserverAddr=nacos:8848\n\tusername=someone\n"
				+ "\tpassword=ab****************************yz\n"))
			.isEqualTo("Nacos client key init properties: \n\tserverAddr=nacos:8848\n\tusername=someone\n\tpassword=***\n");
	}

	/** 口令里可以有逗号和括号，要整个盖住，不能留下后半截 */
	@ParameterizedTest
	@ValueSource(strings = {"jdbc:postgresql://db:5432/mugsun?user=mugsun&password=<P>&ssl=true", "NACOS_PASSWORD=<P>",
			"oldPassword=<P> newPassword=<P>"})
	void passwords(String template) {
		assertThat(LogSecrets.mask(template.replace("<P>", "s3cr,et}(x"))).isEqualTo(template.replace("<P>", "***"));
	}

	/** 一行日志后面跟着异常堆栈，令牌在异常的消息里 */
	@Test
	void tokenInsideAStackTrace() {
		String stack = "\n\tat com.example.Caller.call(Caller.java:12)\n\tat com.example.Main.main(Main.java:5)\n";
		assertThat(LogSecrets.mask("调用失败\njava.lang.IllegalStateException: " + REQUEST_DUMP + stack))
			.isEqualTo("调用失败\njava.lang.IllegalStateException: " + REQUEST_DUMP.replace(TOKEN, "***") + stack);
	}

	/** 同一行过两遍和过一遍一样：日志的出口不小心包了两层也不会把日志改坏 */
	@Test
	void maskingTwiceChangesNothingMore() {
		String once = LogSecrets.mask(REQUEST_DUMP + " /ws/message?token=abc password=xyz " + TOKEN);
		assertThat(once).doesNotContain(TOKEN).doesNotContain("abc").doesNotContain("xyz");
		assertThat(LogSecrets.mask(once)).isEqualTo(once);
	}

	@Test
	void nothingToMask() {
		assertThat(LogSecrets.mask(null)).isNull();
		assertThat(LogSecrets.mask("")).isEmpty();
	}

	/** 长得像但不是凭证的，一个字都不能动 */
	@ParameterizedTest
	@ValueSource(strings = {"Started TrackApplication in 8.2 seconds",
			// 类名里也有 eyJ 和两个点号
			"at com.mugsun.finance.MoneyJournalService.MoneyJournalEntry.toString(MoneyJournalService.java:42)",
			// 只有一段，或者第二段不是 JSON 对象编码出来的
			"etag=eyJhbGciOiJIUzI1NiJ9", "eyJhbGciOiJIUzI1NiJ9.plain-second-part.third-part",
			// 名字只是开头一样
			"accessTokenTtl=18000, tokenTtl=18000", "passwordEncoder=bcrypt",
			// token= 不在地址的查询串里
			"csrf token=abc rejected",
			// 等号后面是空的
			"password= accessToken=, app=unknown /ws/message?token=&v=2"})
	void leavesLookalikesAlone(String text) {
		assertThat(LogSecrets.mask(text)).isEqualTo(text);
	}
}
