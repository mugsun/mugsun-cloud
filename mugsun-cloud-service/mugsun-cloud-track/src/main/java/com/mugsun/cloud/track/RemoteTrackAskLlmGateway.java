package com.mugsun.cloud.track;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.module.TrackAskIntent;
import com.mugsun.boot.common.module.TrackAskLlmGateway;
import com.mugsun.cloud.common.inner.InnerCallException;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.cloud.common.inner.InnerPaths;
import com.mugsun.cloud.common.inner.ServiceNames;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 埋点「问数据」请 ai 服务用大模型解析提问的意图。
 *
 * <p>单体版里 {@code TrackAskService} 拿到的是 AI 模块的 {@code TrackAskLlmGatewayImpl}：装了 AI 模块就用大模型解析，
 * 没装就只用规则解析；大模型调不通时改用规则解析，并提示用户「模型调用失败」。这里照这三种情况对应：
 * <ul>
 *   <li>ai 服务没有部署（或者没配内部调用的密钥）：返回 null，{@code TrackAskService} 不加任何提示、直接用规则解析，
 *       和单体版没装 AI 模块时一样；</li>
 *   <li>ai 服务在但调用没成功（超时、模型没配、模块被关掉）：把异常抛出去，{@code TrackAskService} 会改用规则解析并提示；</li>
 *   <li>调用成功：把意图和参数原样还原。</li>
 * </ul>
 *
 * <p>ai 服务只解析意图，数字仍然全部来自这边的埋点分析；用户有没有权限看对应的数据，也仍然由 {@code TrackAskService} 判断。
 */
@Component
class RemoteTrackAskLlmGateway implements TrackAskLlmGateway {

	private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
	};

	private final InnerClient client;

	private final ServiceNames names;

	private final ObjectMapper objectMapper;

	RemoteTrackAskLlmGateway(InnerClient client, ServiceNames names, ObjectMapper objectMapper) {
		this.client = client;
		this.names = names;
		this.objectMapper = objectMapper;
	}

	@Override
	public TrackAskIntent extract(String question, Integer daysHint) {
		String ai = names.ai();
		if (!client.reachable(ai)) {
			return null;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("question", question);
		body.put("daysHint", daysHint);
		JsonNode data;
		try {
			data = client.callSlow(ai, InnerPaths.AI_TRACK_ASK_INTENT, body);
		} catch (InnerCallException e) {
			if (e.absent()) {
				// 刚查完它还在，发请求时已经下线了：和没有部署一样处理
				return null;
			}
			throw e;
		}
		if (data == null || data.isNull()) {
			return null;
		}
		JsonNode intent = data.get("intent");
		if (!data.isObject() || intent == null || !intent.isTextual()) {
			throw new IllegalStateException(ai + " 返回的意图格式不对");
		}
		JsonNode params = data.get("params");
		Map<String, Object> values = params != null && params.isObject() ? objectMapper.convertValue(params, MAP) : null;
		JsonNode warning = data.get("warning");
		return new TrackAskIntent(intent.textValue(), values,
			warning != null && warning.isTextual() ? warning.textValue() : null);
	}
}
