package com.mugsun.cloud.api.resource;

/**
 * 短信内部接口里两端约好的名字：请求和响应的字段、验证码接口的操作名。
 *
 * <p>调用的一端是 {@link RemoteSmsGateway}，接收的一端是文件和短信服务的 {@code SmsCodeEndpoint} 和 {@code SmsTextEndpoint}，
 * 两边都从这里取，免得写得不一样。路径登记在公共层的 {@code InnerPaths}。
 *
 * <p>验证码接口（{@code InnerPaths.RESOURCE_SMS_CODE}），请求里用 {@code op} 分四种操作：
 * <ul>
 * <li>{@code {"op": "send", "phone": "…"}}：发验证码，响应是空对象；发不出去是业务失败，话和单体版一样</li>
 * <li>{@code {"op": "show"}}：响应 {@code {"show": true 或 false}}，开没开「回显验证码」（只有开发环境开）</li>
 * <li>{@code {"op": "peek", "phone": "…"}}：响应 {@code {"code": "…"}}；没开回显、或者没有有效的验证码时是空对象</li>
 * <li>{@code {"op": "verify", "phone": "…", "code": "…"}}：响应 {@code {"ok": true 或 false}}，通过以后这个验证码作废</li>
 * </ul>
 *
 * <p>文本短信接口（{@code InnerPaths.RESOURCE_SMS_TEXT}）：请求 {@code {"phone": "…", "text": "…"}}，发出去了响应是空对象。
 */
public final class SmsApi {

	public static final String FIELD_OP = "op";

	public static final String FIELD_PHONE = "phone";

	public static final String FIELD_CODE = "code";

	public static final String FIELD_TEXT = "text";

	public static final String FIELD_SHOW = "show";

	public static final String FIELD_OK = "ok";

	public static final String OP_SEND = "send";

	public static final String OP_SHOW = "show";

	public static final String OP_PEEK = "peek";

	public static final String OP_VERIFY = "verify";

	private SmsApi() {
	}
}
