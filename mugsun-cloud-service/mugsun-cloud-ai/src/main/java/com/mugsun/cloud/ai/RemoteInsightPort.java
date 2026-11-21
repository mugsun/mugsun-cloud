package com.mugsun.cloud.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.module.ModuleInsightPort;
import com.mugsun.cloud.common.inner.InnerCallException;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.core.tool.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向别的服务要它那个模块的库内实数。
 *
 * <p>单体版里 {@code AiWeaveController} 直接调 GIS、埋点模块的 {@link ModuleInsightPort}；这里换成调对方服务的内部接口，
 * 对方还是用 mugsun-boot 的那个实现取数。
 *
 * <p>{@code AiWeaveController} 会把异常的消息给用户看，所以调用失败时这里只抛写给用户的话，
 * 具体原因（地址、状态码、验签这些）只记日志。对方自己抛出的业务提示（比如模块被关掉了）原样转给用户，和单体版一样。
 */
class RemoteInsightPort implements ModuleInsightPort {

	private static final Logger log = LoggerFactory.getLogger(RemoteInsightPort.class);

	private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
	};

	private final String module;

	private final String serviceId;

	private final String path;

	/** 对方服务在提示里的叫法，比如「地理信息」 */
	private final String label;

	/** 要查的东西在提示里的叫法，比如「图层」 */
	private final String subject;

	private final InnerClient client;

	private final ObjectMapper objectMapper;

	RemoteInsightPort(String module, String serviceId, String path, String label, String subject, InnerClient client,
					  ObjectMapper objectMapper) {
		this.module = module;
		this.serviceId = serviceId;
		this.path = path;
		this.label = label;
		this.subject = subject;
		this.client = client;
		this.objectMapper = objectMapper;
	}

	@Override
	public String module() {
		return module;
	}

	@Override
	public Map<String, Object> snapshot(Map<String, Object> request) {
		JsonNode data;
		try {
			data = client.call(serviceId, path, request == null ? Map.of() : request);
		} catch (ServiceException e) {
			throw e;
		} catch (InnerCallException e) {
			log.warn("向 {} 查询{}失败：{}", serviceId, subject, e.getMessage());
			throw new ServiceException(switch (e.kind()) {
				case NO_INSTANCE -> offline();
				case NOT_CONFIGURED -> "服务之间的内部调用没有开通，无法查询" + subject + "，请联系管理员";
				// 对方在，只是这会儿处理不过来：照实说，免得让人以为是坏了
				case BUSY -> label + "服务正忙，请稍后再试";
				default -> unavailable();
			});
		} catch (RuntimeException e) {
			log.warn("向 {} 查询{}失败", serviceId, subject, e);
			throw new ServiceException(unavailable());
		}
		if (data == null || !data.isObject()) {
			log.warn("{} 的 {} 返回的不是 JSON 对象，不采用", serviceId, path);
			throw new ServiceException(unavailable());
		}
		return objectMapper.convertValue(data, MAP);
	}

	private String unavailable() {
		return label + "服务暂时不可用，请稍后再试";
	}

	/**
	 * 注册中心里没有对方的实例时说的话。可能是没有部署，也可能是它正在重启或者挂了，这里分不出来，
	 * 所以不说「没有部署」：部署了、只是此刻不在的时候，那句话会让人以为是装漏了。
	 */
	private String offline() {
		return label + "服务现在不在线，无法查询" + subject + "。请稍后再试，一直不行请联系管理员";
	}
}
