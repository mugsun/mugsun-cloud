package com.mugsun.cloud.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 监控中心启得起来，登录是管用的。不连 Nacos：页面上有没有实例、实例在不在线，要在整套部署起来以后看。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.nacos.config.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false",
	"spring.security.user.name=watcher",
	"spring.security.user.password=only-for-this-test"})
class AdminApplicationTest {

	@LocalServerPort
	private int port;

	private WebTestClient client;

	@BeforeEach
	void connect() {
		client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
	}

	@Test
	void pageAndApiNeedLogin() {
		client.get().uri("/").accept(MediaType.TEXT_HTML).exchange()
			.expectStatus().isFound().expectHeader().valueEquals(HttpHeaders.LOCATION, "/login");
		client.get().uri("/applications").accept(MediaType.APPLICATION_JSON).exchange()
			.expectStatus().isFound().expectHeader().valueEquals(HttpHeaders.LOCATION, "/login");
	}

	@Test
	void loginPageAndOwnHealthAreOpen() {
		client.get().uri("/login").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk();
		client.get().uri("/actuator/health").exchange().expectStatus().isOk()
			.expectBody().jsonPath("$.status").isEqualTo("UP");
	}

	@Test
	void configuredAccountGetsIn() {
		ResponseCookie session = login("watcher", "only-for-this-test", "/");
		assertThat(session).as("登录以后的会话 Cookie").isNotNull();

		client.get().uri("/applications").accept(MediaType.APPLICATION_JSON).cookie(session.getName(), session.getValue())
			.exchange().expectStatus().isOk();
	}

	@Test
	void wrongPasswordStaysOut() {
		login("watcher", "wrong", "/login?error");
	}

	/** 提交登录表单，确认被带到 expectedLocation，返回会话的 Cookie（没有就是 null） */
	private ResponseCookie login(String username, String password, String expectedLocation) {
		var result = client.post().uri("/login")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData("username", username).with("password", password))
			.exchange()
			.expectStatus().isFound().expectHeader().valueEquals(HttpHeaders.LOCATION, expectedLocation)
			.expectBody().isEmpty();
		return result.getResponseCookies().getFirst("SESSION");
	}
}
