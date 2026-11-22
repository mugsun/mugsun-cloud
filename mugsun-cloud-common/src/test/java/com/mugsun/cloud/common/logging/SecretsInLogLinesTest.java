package com.mugsun.cloud.common.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务的日志里不留凭证：控制台、服务名.log 和只记错误的 服务名-error.log，三个出口写出去的都打过码。
 * 打码是 mugsun-cloud-config 带进来的（LogSecretsMasking），认哪些形状在那边的 LogSecretsTest 里；
 * 这里确认它在服务用的这份 logback-spring.xml 上是生效的。日志是照服务启动的样子初始化的，见 {@link ServiceLoggingTestBase}。
 */
class SecretsInLogLinesTest extends ServiceLoggingTestBase {

	/**
	 * 假的令牌，形状和 Nacos 发的访问令牌一样。拼出来而不是整串写在这里，免得扫密钥的工具把它当成真的。
	 */
	private static final String TOKEN = String.join(".", "eyJ" + "hbGciOiJIUzI1NiJ9", "eyJ" + "zdWIiOiJtdWdzdW4ifQ",
		"c2lnbmF0dXJlLWZha2U_-x");

	/** Nacos 的客户端发请求失败时打的那一行，照真的日志抄的，令牌换成了假的 */
	private static final String REQUEST_DUMP = "Send request fail, request = InstanceRequest{headers={accessToken=" + TOKEN
			+ ", app=unknown}, requestId='null'}, retryTimes = 0, errorMessage = Client not connected, current status:UNHEALTHY";

	@Test
	void nacosAccessTokenIsMaskedOnTheConsoleAndInBothFiles(CapturedOutput output) throws Exception {
		initializeLoggingTheWayAServiceDoes();
		Logger nacos = LoggerFactory.getLogger("com.alibaba.nacos.common.remote.client");

		nacos.error(REQUEST_DUMP);
		// 令牌在异常的消息里，跟着堆栈一起打出来
		nacos.error("注销实例失败", new IllegalStateException(REQUEST_DUMP));

		assertMasked("控制台", output.getOut());
		assertMasked("日志文件", logFile(SERVICE + ".log"));
		assertMasked("只记错误的日志文件", logFile(SERVICE + "-error.log"));
	}

	private static void assertMasked(String where, String written) {
		assertThat(written).as(where).doesNotContain(TOKEN);
		assertThat(written.lines().filter(line -> line.contains("headers={accessToken=***, app=unknown}")))
			.as("%s里令牌换成了星号的行：日志的消息一行，异常的消息一行", where)
			.hasSize(2);
	}
}
