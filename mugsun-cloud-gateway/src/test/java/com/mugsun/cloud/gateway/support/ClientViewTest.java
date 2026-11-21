package com.mugsun.cloud.gateway.support;

import com.mugsun.boot.common.web.IpCidr;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端地址和对外协议、主机、端口的取法：直接连上来的地址不是可信代理时，它带的转发头一概不认；
 * 是可信代理时，按和 mugsun-boot 的 ClientIpResolver 相同的规则取客户端地址。
 */
class ClientViewTest {

	private static final List<IpCidr> NO_PROXY = List.of();
	private static final List<IpCidr> PROXIES = IpCidr.parseList("10.0.0.0/8");
	private static final URI GATEWAY_URI = URI.create("http://api.example.com:8080/system/user/list");

	private static HttpHeaders headers(String... pairs) {
		HttpHeaders headers = new HttpHeaders();
		for (int i = 0; i < pairs.length; i += 2) {
			headers.add(pairs[i], pairs[i + 1]);
		}
		return headers;
	}

	@Test
	void forwardedHeadersIgnoredWithoutTrustedProxies() {
		ClientView view = ClientView.resolve("203.0.113.9", GATEWAY_URI,
			headers("X-Forwarded-For", "1.1.1.1", "X-Forwarded-Proto", "https", "X-Forwarded-Host", "evil.example.com",
				"X-Forwarded-Port", "443"),
			NO_PROXY);
		assertThat(view).isEqualTo(new ClientView("203.0.113.9", "http", "api.example.com", 8080));
	}

	@Test
	void forwardedHeadersIgnoredWhenPeerNotTrusted() {
		ClientView view = ClientView.resolve("203.0.113.9", GATEWAY_URI,
			headers("X-Forwarded-For", "1.1.1.1", "X-Forwarded-Proto", "https", "X-Forwarded-Host", "evil.example.com"),
			PROXIES);
		assertThat(view).isEqualTo(new ClientView("203.0.113.9", "http", "api.example.com", 8080));
	}

	@Test
	void clientIpIsFirstUntrustedHopFromTheRight() {
		ClientView view = ClientView.resolve("10.0.0.9", GATEWAY_URI,
			headers("X-Forwarded-For", "1.1.1.1, 198.51.100.7, 10.0.0.3"), PROXIES);
		assertThat(view.clientIp()).isEqualTo("198.51.100.7");
	}

	@Test
	void repeatedForwardedForLinesAreReadAsOneList() {
		ClientView view = ClientView.resolve("10.0.0.9", GATEWAY_URI,
			headers("X-Forwarded-For", "1.1.1.1", "X-Forwarded-For", "198.51.100.7"), PROXIES);
		assertThat(view.clientIp()).isEqualTo("198.51.100.7");
	}

	@Test
	void invalidHopFallsBackToPeer() {
		ClientView view = ClientView.resolve("10.0.0.9", GATEWAY_URI,
			headers("X-Forwarded-For", "evil.example.com, 10.0.0.3"), PROXIES);
		assertThat(view.clientIp()).isEqualTo("10.0.0.9");
	}

	@Test
	void hopThatOnlyLooksLikeAnAddressFallsBackToPeer() {
		// 10.0.0.300 不是地址。JDK 会把它当成主机名去查 DNS，在什么名字都解析得出地址的网络里（测试里的 FakeIpDns 模仿的就是这种）
		// 查回一个不相干的地址。这里必须按「不是 IP」处理，退回直接连上来的地址
		for (String hop : List.of("10.0.0.300", "999.1.1.1", "256.256.256.256")) {
			ClientView view = ClientView.resolve("10.0.0.9", GATEWAY_URI,
				headers("X-Forwarded-For", hop + ", 10.0.0.3"), PROXIES);
			assertThat(view.clientIp()).as(hop).isEqualTo("10.0.0.9");
			assertThat(ClientView.canonicalIp(hop)).as(hop).isNull();
		}
		assertThat(FakeIpDns.lookups()).doesNotContain("10.0.0.300", "999.1.1.1", "256.256.256.256");
	}

	@Test
	void peerIsUsedWhenEveryHopIsTrustedOrHeaderMissing() {
		assertThat(ClientView.resolve("10.0.0.9", GATEWAY_URI, headers("X-Forwarded-For", "10.0.0.3, 10.0.0.4"), PROXIES)
			.clientIp()).isEqualTo("10.0.0.9");
		assertThat(ClientView.resolve("10.0.0.9", GATEWAY_URI, headers(), PROXIES).clientIp()).isEqualTo("10.0.0.9");
	}

	@Test
	void schemeHostAndPortComeFromTrustedProxy() {
		ClientView view = ClientView.resolve("10.0.0.9", URI.create("http://gateway:8080/ping"),
			headers("X-Forwarded-For", "198.51.100.7", "X-Forwarded-Proto", "https", "X-Forwarded-Host",
				"app.example.com"),
			PROXIES);
		assertThat(view).isEqualTo(new ClientView("198.51.100.7", "https", "app.example.com", 443));
	}

	@Test
	void proxyThatOnlySendsProtoKeepsHostHeader() {
		// mugsun-pc 的 Nginx 就是这样：Host 透传（不带端口），只多给一个 X-Forwarded-Proto
		ClientView view = ClientView.resolve("10.0.0.9", URI.create("http://app.example.com/ping"),
			headers("X-Forwarded-For", "198.51.100.7", "X-Forwarded-Proto", "https"), PROXIES);
		assertThat(view).isEqualTo(new ClientView("198.51.100.7", "https", "app.example.com", 443));
	}

	@Test
	void forwardedPortOverridesPortInForwardedHost() {
		assertThat(ClientView.resolve("10.0.0.9", GATEWAY_URI,
			headers("X-Forwarded-Host", "app.example.com:8443"), PROXIES).port()).isEqualTo(8443);
		assertThat(ClientView.resolve("10.0.0.9", GATEWAY_URI,
			headers("X-Forwarded-Host", "app.example.com:8443", "X-Forwarded-Port", "9443"), PROXIES).port())
			.isEqualTo(9443);
	}

	@Test
	void protoChainIsSecureOnlyWhenEveryHopIsHttps() {
		assertThat(ClientView.resolve("10.0.0.9", GATEWAY_URI, headers("X-Forwarded-Proto", "https, https"), PROXIES)
			.scheme()).isEqualTo("https");
		assertThat(ClientView.resolve("10.0.0.9", GATEWAY_URI, headers("X-Forwarded-Proto", "https, http"), PROXIES)
			.scheme()).isEqualTo("http");
	}

	@Test
	void malformedProtoHostAndPortAreNotAdopted() {
		ClientView view = ClientView.resolve("10.0.0.9", GATEWAY_URI,
			headers("X-Forwarded-Proto", "javascript", "X-Forwarded-Host", "a b<script>", "X-Forwarded-Port", "99999"),
			PROXIES);
		assertThat(view).isEqualTo(new ClientView("10.0.0.9", "http", "api.example.com", 8080));
	}

	@Test
	void defaultPortFollowsScheme() {
		assertThat(ClientView.resolve("203.0.113.9", URI.create("http://example.com/ping"), headers(), NO_PROXY).port())
			.isEqualTo(80);
		assertThat(ClientView.resolve("203.0.113.9", URI.create("https://example.com/ping"), headers(), NO_PROXY))
			.isEqualTo(new ClientView("203.0.113.9", "https", "example.com", 443));
	}

	@Test
	void ipv6AddressesAreWrittenInOneForm() {
		assertThat(ClientView.resolve("[::1]", GATEWAY_URI, headers(), NO_PROXY).clientIp())
			.isEqualTo("0:0:0:0:0:0:0:1");
		assertThat(ClientView.resolve("fe80::1%en0", GATEWAY_URI, headers(), NO_PROXY).clientIp())
			.isEqualTo("fe80:0:0:0:0:0:0:1");
		assertThat(ClientView.canonicalIp("::ffff:192.0.2.1")).isEqualTo("192.0.2.1");
		assertThat(ClientView.canonicalIp("not-an-ip")).isNull();
	}

	@Test
	void unknownPeerGivesEmptyClientIp() {
		assertThat(ClientView.resolve(null, GATEWAY_URI, headers("X-Forwarded-For", "1.1.1.1"), PROXIES).clientIp())
			.isEmpty();
	}
}
