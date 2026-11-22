package com.mugsun.cloud.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关的日志里不留凭证。打码是 mugsun-cloud-config 带进来的（LogSecretsMasking），认哪些形状在那边的 LogSecretsTest 里；
 * 这里确认它在一个跑起来的网关上是生效的。网关自己记的日志本来就不带这些（见 GatewayAccessLogTest），
 * 要防的是别人打的：Nacos 的客户端、框架。所以照它们的样子打一行，看写出去的是什么。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.nacos.config.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false"})
@ExtendWith(OutputCaptureExtension.class)
class GatewayLogSecretsTest extends GatewayTestBase {

	/**
	 * 假的令牌，形状和 Nacos 发的访问令牌一样。拼出来而不是整串写在这里，免得扫密钥的工具把它当成真的。
	 */
	private static final String TOKEN = String.join(".", "eyJ" + "hbGciOiJIUzI1NiJ9", "eyJ" + "zdWIiOiJtdWdzdW4ifQ",
		"c2lnbmF0dXJlLWZha2U_-x");

	/** 发给 Nacos 的请求失败时，Nacos 的客户端把请求头连同访问令牌一起打出来。照真的日志抄的，令牌换成了假的 */
	@Test
	void nacosAccessTokenIsMasked(CapturedOutput output) {
		LoggerFactory.getLogger("com.alibaba.nacos.common.remote.client")
			.error("Send request fail, request = InstanceRequest{headers={accessToken=" + TOKEN
					+ ", app=unknown}, requestId='null'}, retryTimes = 0, errorMessage = Client not connected, current status:UNHEALTHY");

		assertThat(output.getOut()).contains("request = InstanceRequest{headers={accessToken=***, app=unknown}, requestId='null'}");
		assertThat(output.getAll()).doesNotContain(TOKEN);
	}

	/** 框架在个别出错场景下会把完整的地址记进日志，站内信的 WebSocket 地址里带着登录令牌 */
	@Test
	void loginTokenInAnAddressIsMasked(CapturedOutput output) {
		LoggerFactory.getLogger("org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler")
			.error("[1a2b3c4d-7]  500 Server Error for HTTP GET \"/ws/message?token=login-token-0f3c9a&v=2\"");

		assertThat(output.getOut()).contains("500 Server Error for HTTP GET \"/ws/message?token=***&v=2\"");
		assertThat(output.getAll()).doesNotContain("login-token-0f3c9a");
	}
}
