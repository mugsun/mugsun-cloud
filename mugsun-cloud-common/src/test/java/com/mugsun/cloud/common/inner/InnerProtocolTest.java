package com.mugsun.cloud.common.inner;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InnerProtocolTest {

	/** 测试用的固定密钥，不是任何环境的真实密钥 */
	private static final byte[] KEY = "test-only-key-0123456789abcdef-XYZ".getBytes(StandardCharsets.UTF_8);

	private static final String PATH = "/inner/gis/insight/snapshot";

	private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAA";

	private static final byte[] BODY = "{\"layer\":\"路网\"}".getBytes(StandardCharsets.UTF_8);

	/**
	 * 标准答案是用 openssl 另算的（不经过这里的代码），用来锁住签名的拼法：拼法一改，新旧版本的服务就互相调不通了。
	 * <pre>
	 * printf 'mugsun-inner-v1-request\nPOST\n/inner/gis/insight/snapshot\n1700000000000\nAAAAAAAAAAAAAAAAAAAAAA\n
	 *   mugsun-ai\nmugsun-gis\n000000\n&lt;请求体的 sha256&gt;' | openssl dgst -sha256 -hmac &lt;密钥&gt;
	 * </pre>
	 */
	@Test
	void requestSignatureMatchesIndependentlyComputedValue() {
		assertThat(sign("POST", PATH, "1700000000000", NONCE, "mugsun-ai", "mugsun-gis", "000000", BODY))
			.isEqualTo("c27c4efb02b87f1e7ea8832f19e50f70231240edec0f8fd0c98a445a5b8685cb");
	}

	@Test
	void responseSignatureMatchesIndependentlyComputedValue() {
		byte[] body = "{\"ok\":true,\"data\":{\"count\":3}}".getBytes(StandardCharsets.UTF_8);

		assertThat(InnerProtocol.signResponse(KEY, PATH, NONCE, "mugsun-ai", "mugsun-gis", body))
			.isEqualTo("5d9464534f5918a73ded9bab89714fdc208259b42b986f3fc3ea19b7ed79c33b");
	}

	@Test
	void changingAnySignedPartChangesTheRequestSignature() {
		List<String> signatures = new ArrayList<>();
		signatures.add(sign("POST", PATH, "1700000000000", NONCE, "mugsun-ai", "mugsun-gis", "000000", BODY));
		signatures.add(sign("PUT", PATH, "1700000000000", NONCE, "mugsun-ai", "mugsun-gis", "000000", BODY));
		signatures.add(sign("POST", "/inner/gis/insight/other", "1700000000000", NONCE, "mugsun-ai", "mugsun-gis",
			"000000", BODY));
		signatures.add(sign("POST", PATH, "1700000000001", NONCE, "mugsun-ai", "mugsun-gis", "000000", BODY));
		signatures.add(sign("POST", PATH, "1700000000000", "BAAAAAAAAAAAAAAAAAAAAA", "mugsun-ai", "mugsun-gis", "000000",
			BODY));
		signatures.add(sign("POST", PATH, "1700000000000", NONCE, "mugsun-system", "mugsun-gis", "000000", BODY));
		signatures.add(sign("POST", PATH, "1700000000000", NONCE, "mugsun-ai", "mugsun-track", "000000", BODY));
		signatures.add(sign("POST", PATH, "1700000000000", NONCE, "mugsun-ai", "mugsun-gis", "-1", BODY));
		signatures.add(sign("POST", PATH, "1700000000000", NONCE, "mugsun-ai", "mugsun-gis", "000000",
			"{\"layer\":\"水系\"}".getBytes(StandardCharsets.UTF_8)));
		signatures.add(InnerProtocol.signRequest("another-key-0123456789abcdef-XYZ---".getBytes(StandardCharsets.UTF_8),
			"POST", PATH, "1700000000000", NONCE, "mugsun-ai", "mugsun-gis", "000000", BODY));

		assertThat(new HashSet<>(signatures)).hasSize(signatures.size());
		assertThat(signatures).allMatch(s -> InnerProtocol.SIGNATURE.matcher(s).matches());
	}

	/** 调用方和目标对调不能得到同一个签名：发给甲的请求不能转给乙用 */
	@Test
	void callerAndTargetAreNotInterchangeable() {
		assertThat(sign("POST", PATH, "1700000000000", NONCE, "mugsun-ai", "mugsun-gis", "000000", BODY))
			.isNotEqualTo(sign("POST", PATH, "1700000000000", NONCE, "mugsun-gis", "mugsun-ai", "000000", BODY));
	}

	/** 请求的签名不能拿来冒充响应的签名 */
	@Test
	void requestAndResponseSignaturesAreSeparateDomains() {
		assertThat(InnerProtocol.signResponse(KEY, PATH, NONCE, "mugsun-ai", "mugsun-gis", BODY))
			.isNotEqualTo(sign("POST", PATH, "1700000000000", NONCE, "mugsun-ai", "mugsun-gis", "000000", BODY));
	}

	@Test
	void responseSignatureIsBoundToTheRequestNonceAndBody() {
		String base = InnerProtocol.signResponse(KEY, PATH, NONCE, "mugsun-ai", "mugsun-gis", BODY);

		assertThat(InnerProtocol.signResponse(KEY, PATH, "BAAAAAAAAAAAAAAAAAAAAA", "mugsun-ai", "mugsun-gis", BODY))
			.isNotEqualTo(base);
		assertThat(InnerProtocol.signResponse(KEY, PATH, NONCE, "mugsun-ai", "mugsun-gis", new byte[0]))
			.isNotEqualTo(base);
		assertThat(InnerProtocol.signResponse(KEY, "/inner/gis/insight/other", NONCE, "mugsun-ai", "mugsun-gis", BODY))
			.isNotEqualTo(base);
	}

	@Test
	void sameSignatureComparesWholeValueAndRejectsNull() {
		String signature = sign("POST", PATH, "1700000000000", NONCE, "mugsun-ai", "mugsun-gis", "000000", BODY);

		assertThat(InnerProtocol.sameSignature(signature, signature)).isTrue();
		assertThat(InnerProtocol.sameSignature(signature, signature.substring(0, 63))).isFalse();
		assertThat(InnerProtocol.sameSignature(signature, signature.toUpperCase())).isFalse();
		assertThat(InnerProtocol.sameSignature(signature, null)).isFalse();
		assertThat(InnerProtocol.sameSignature(null, signature)).isFalse();
	}

	@Test
	void pathRule() {
		assertThat(List.of("/inner/gis/insight/snapshot", "/inner/ai/track-ask/intent", "/inner/a", "/inner/a1/b-2"))
			.allMatch(p -> InnerProtocol.PATH.matcher(p).matches());
		assertThat(List.of("/inner", "/inner/", "/inner//a", "/inner/a/", "/inner/A", "/inner/a_b", "/inner/a.b",
			"/inner/a?x=1", "/inner/a;b", "/inner/%61", "/inner/../system", "/inner/-a", "/api/inner/a", "inner/a",
			"/inner/a\nb"))
			.noneMatch(p -> InnerProtocol.PATH.matcher(p).matches());
	}

	/** 写进签名串的每一项都不能含换行或空白，否则两组不同的值可能拼出同一个串 */
	@Test
	void headerRulesRejectLineBreaksAndBlanks() {
		for (String bad : List.of("a\nb", "a\rb", "a b", "a\tb", "", "租户")) {
			assertThat(InnerProtocol.SERVICE_NAME.matcher(bad).matches()).as("service " + bad).isFalse();
			assertThat(InnerProtocol.TENANT.matcher(bad).matches()).as("tenant " + bad).isFalse();
			assertThat(InnerProtocol.NONCE.matcher(bad).matches()).as("nonce " + bad).isFalse();
			assertThat(InnerProtocol.TIMESTAMP.matcher(bad).matches()).as("timestamp " + bad).isFalse();
		}
		assertThat(InnerProtocol.TENANT.matcher("000000").matches()).isTrue();
		assertThat(InnerProtocol.TENANT.matcher("-1").matches()).isTrue();
		assertThat(InnerProtocol.TENANT.matcher("x".repeat(65)).matches()).isFalse();
		assertThat(InnerProtocol.SERVICE_NAME.matcher("mugsun-gis").matches()).isTrue();
		assertThat(InnerProtocol.NONCE.matcher("short").matches()).isFalse();
		assertThat(InnerProtocol.TIMESTAMP.matcher("-1700000000000").matches()).isFalse();
	}

	private static String sign(String method, String path, String timestamp, String nonce, String caller, String target,
							   String tenant, byte[] body) {
		return InnerProtocol.signRequest(KEY, method, path, timestamp, nonce, caller, target, tenant, body);
	}
}
