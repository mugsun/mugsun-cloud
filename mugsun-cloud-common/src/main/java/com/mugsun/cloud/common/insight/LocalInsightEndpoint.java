package com.mugsun.cloud.common.insight;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.module.ModuleInsightPort;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.cloud.common.inner.InnerEndpoint;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 把本服务里的 {@link ModuleInsightPort} 开放成内部接口，让 ai 服务取到这个模块的库内实数。
 *
 * <p>单体版里 AI 模块直接调 GIS、埋点模块的 {@code ModuleInsightPort}；拆开以后它们不在一个进程里，
 * 由各自的服务用这个类把本地的实现开放出去，ai 服务那边用远程实现调过来。取数的逻辑一行没动。
 *
 * <p>和单体版一样，这里只按租户取数，不看是哪个用户：用户有没有登录由 ai 服务的接口判断。
 */
public class LocalInsightEndpoint implements InnerEndpoint {

	private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
	};

	private final String path;

	private final String module;

	private final String missingMessage;

	private final Supplier<List<ModuleInsightPort>> ports;

	private final ObjectMapper objectMapper;

	/**
	 * @param path           内部接口的路径
	 * @param module         要开放的模块，对应 {@link ModuleInsightPort#module()}
	 * @param missingMessage 本服务里没有这个模块的实现时给用户看的话，用单体版的原话
	 */
	protected LocalInsightEndpoint(String path, String module, String missingMessage,
								   ObjectProvider<ModuleInsightPort> ports, ObjectMapper objectMapper) {
		this(path, module, missingMessage, () -> ports.orderedStream().toList(), objectMapper);
	}

	LocalInsightEndpoint(String path, String module, String missingMessage, Supplier<List<ModuleInsightPort>> ports,
						 ObjectMapper objectMapper) {
		this.path = path;
		this.module = module;
		this.missingMessage = missingMessage;
		this.ports = ports;
		this.objectMapper = objectMapper;
	}

	@Override
	public String path() {
		return path;
	}

	@Override
	public Object handle(InnerCall call) {
		JsonNode body = call.body();
		if (body == null || !body.isObject()) {
			throw new ServiceException("请求体要是一个 JSON 对象");
		}
		ModuleInsightPort port = null;
		for (ModuleInsightPort candidate : ports.get()) {
			if (module.equals(candidate.module())) {
				port = candidate;
				break;
			}
		}
		if (port == null) {
			throw new ServiceException(missingMessage);
		}
		Map<String, Object> request = objectMapper.convertValue(body, MAP);
		return port.snapshot(request);
	}
}
