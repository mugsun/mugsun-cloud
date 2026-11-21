package com.mugsun.cloud.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.module.TrackAskIntent;
import com.mugsun.boot.common.module.TrackAskLlmGateway;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.cloud.common.inner.InnerEndpoint;
import com.mugsun.cloud.common.inner.InnerPaths;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内部接口：用大模型把埋点问答的提问解析成意图，给 track 服务的「问数据」用。
 *
 * <p>解析用的是 mugsun-boot 的 {@code TrackAskLlmGatewayImpl}，这里只负责收发。这个接口只解析意图和参数，
 * 不读也不出任何埋点数字；用户能不能看那类数据，由 track 服务拿到意图以后自己判断。
 *
 * <p>请求：{@code {"question": "提问", "daysHint": 7}}，{@code daysHint} 可以没有。
 * 响应：{@code {"intent": "…", "params": {…}, "warning": "…"}}；没有结果时是 null。
 */
@Component
class TrackAskIntentEndpoint implements InnerEndpoint {

	private final ObjectProvider<TrackAskLlmGateway> gateway;

	TrackAskIntentEndpoint(ObjectProvider<TrackAskLlmGateway> gateway) {
		this.gateway = gateway;
	}

	@Override
	public String path() {
		return InnerPaths.AI_TRACK_ASK_INTENT;
	}

	@Override
	public Object handle(InnerCall call) {
		JsonNode body = call.body();
		JsonNode question = body == null ? null : body.get("question");
		if (question == null || !question.isTextual() || question.textValue().isBlank()) {
			throw new ServiceException("请求里没有要解析的提问");
		}
		Integer daysHint = daysHint(body.get("daysHint"));
		TrackAskLlmGateway local = gateway.getIfAvailable();
		if (local == null) {
			throw new ServiceException("AI 模块未装配，无法解析提问");
		}
		TrackAskIntent intent = local.extract(question.textValue(), daysHint);
		if (intent == null) {
			return null;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("intent", intent.intent());
		out.put("params", intent.params());
		out.put("warning", intent.warning());
		return out;
	}

	private static Integer daysHint(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isIntegralNumber() || !node.canConvertToInt()) {
			throw new ServiceException("请求里的 daysHint 要是整数");
		}
		return node.intValue();
	}
}
