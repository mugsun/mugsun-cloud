package com.mugsun.cloud.common.inner;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 内部调用的约定：请求头叫什么、每个值允许什么字符、签名怎么算。调用方（{@link InnerClient}）和被调用方
 * （{@link InnerRequestFilter}）都从这里取，保证两边算法一致。
 *
 * <p>签名是 HMAC-SHA256，密钥是 {@link InnerProperties} 里的共享密钥。请求的签名覆盖：方法、路径、时间、随机串、
 * 调用方服务名、目标服务名、租户、请求体的摘要。这几项任何一项被改动，或者把发给甲服务的请求转给乙服务，
 * 验签都会失败。响应也签名，并带上请求的随机串：调用方据此确认回话的确实是拿着密钥的服务，而且回的是这一次请求。
 *
 * <p>各项用换行连接。每一项的格式都先按下面的规则校验过，不含换行，所以不会出现两组不同的值拼出同一个串的情况。
 *
 * <p>被调用方签了名的响应有三种：成功（{@code ok} 为 true）、业务失败（{@link #CODE_BUSINESS}）、
 * 忙（{@link #CODE_BUSY}，这次没有执行）。
 */
final class InnerProtocol {

	static final String HEADER_CALLER = "X-Mugsun-Inner-Caller";

	static final String HEADER_TENANT = "X-Mugsun-Inner-Tenant";

	static final String HEADER_TIMESTAMP = "X-Mugsun-Inner-Timestamp";

	static final String HEADER_NONCE = "X-Mugsun-Inner-Nonce";

	static final String HEADER_SIGNATURE = "X-Mugsun-Inner-Signature";

	/**
	 * 调用方最多等多少毫秒。被调用方据此给处理这次调用时发出的查询设时限，调用方不等了，查询也不再跑。
	 *
	 * <p>这一项不在签名里：加进签名的话，滚动升级期间新旧版本的服务互相验不过签。它只决定查询最多跑多久，
	 * 被调用方还会把它限制在 1 秒到 {@code long-read-timeout} 之间，改它换不来别的东西。没带或者格式不对时，
	 * 被调用方不设时限，和没有这一项的旧版本一样。
	 */
	static final String HEADER_WAIT = "X-Mugsun-Inner-Wait";

	/** 响应里的代码：业务上的失败，消息要原样带给用户 */
	static final String CODE_BUSINESS = "business";

	/** 响应里的代码：这个接口同时在处理的请求到了上限，这次没有执行，调用方可以换一个实例 */
	static final String CODE_BUSY = "busy";

	/** 服务名（spring.application.name） */
	static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	/** 租户编号，或者表示「不按租户过滤」的 -1：可见的 ASCII 字符，不含空白 */
	static final Pattern TENANT = Pattern.compile("[\\x21-\\x7E]{1,64}");

	/** 毫秒时间戳 */
	static final Pattern TIMESTAMP = Pattern.compile("[0-9]{1,15}");

	static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,64}");

	/** 等待的毫秒数 */
	static final Pattern WAIT = Pattern.compile("[0-9]{1,9}");

	/** 签名：64 位小写十六进制 */
	static final Pattern SIGNATURE = Pattern.compile("[0-9a-f]{64}");

	/** 接口路径：/inner/ 开头，每段只用小写字母、数字和短横线 */
	static final Pattern PATH = Pattern.compile("/inner(/[a-z0-9][a-z0-9-]{0,63}){1,8}");

	private static final String REQUEST_TAG = "mugsun-inner-v1-request";

	private static final String RESPONSE_TAG = "mugsun-inner-v1-response";

	private static final String HMAC = "HmacSHA256";

	private InnerProtocol() {
	}

	static String signRequest(byte[] key, String method, String path, String timestamp, String nonce, String caller,
							  String target, String tenant, byte[] body) {
		return hmac(key, String.join("\n", REQUEST_TAG, method, path, timestamp, nonce, caller, target, tenant,
			sha256(body)));
	}

	static String signResponse(byte[] key, String path, String nonce, String caller, String target, byte[] body) {
		return hmac(key, String.join("\n", RESPONSE_TAG, path, nonce, caller, target, sha256(body)));
	}

	/** 比较两个签名。用时不随「前面有几位相同」变化，不能靠计时一位一位地猜 */
	static boolean sameSignature(String expected, String provided) {
		if (expected == null || provided == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
			provided.getBytes(StandardCharsets.UTF_8));
	}

	private static String hmac(byte[] key, String text) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(new SecretKeySpec(key, HMAC));
			return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("计算内部调用的签名失败", e);
		}
	}

	private static String sha256(byte[] body) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("计算内部调用的摘要失败", e);
		}
	}
}
