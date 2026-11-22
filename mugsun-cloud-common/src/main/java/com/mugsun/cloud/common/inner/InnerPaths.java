package com.mugsun.cloud.common.inner;

/**
 * 现有的内部接口。调用方和被调用方在不同的服务里，路径都从这里取，免得两边写得不一样。
 * 新增内部接口时在这里登记，并写明由哪个服务提供、做什么。
 */
public final class InnerPaths {

	/** gis 服务提供：图层的库内实数（有几个图层、各有多少要素），给 AI 解读用 */
	public static final String GIS_INSIGHT_SNAPSHOT = "/inner/gis/insight/snapshot";

	/** track 服务提供：埋点的库内实数（接入了哪些应用、最近 7 天的概览），给 AI 解读用 */
	public static final String TRACK_INSIGHT_SNAPSHOT = "/inner/track/insight/snapshot";

	/** ai 服务提供：用大模型把埋点问答的提问解析成意图（只解析意图，不出数字） */
	public static final String AI_TRACK_ASK_INTENT = "/inner/ai/track-ask/intent";

	/** ai 服务提供：用大模型把一句话的建表描述变成代码生成的草稿 */
	public static final String AI_GEN_DRAFT = "/inner/ai/gen/draft";

	/** 文件和短信服务提供：短信验证码的发送、回显和校验，给 system 里的登录用 */
	public static final String RESOURCE_SMS_CODE = "/inner/resource/sms/code";

	/** 文件和短信服务提供：发一条文本短信，给 system 里的双因子登录和短信通知用 */
	public static final String RESOURCE_SMS_TEXT = "/inner/resource/sms/text";

	/** 审批流服务提供：一个用户的待办列表，给 system 里的手机端首页用 */
	public static final String FLOW_APP_TODOS = "/inner/flow/app/todos";

	private InnerPaths() {
	}
}
