package com.mugsun.cloud.ai;

import com.mugsun.boot.ai.AiModuleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ai 服务启动时确认 AI 模块确实装配上了。
 *
 * <p>{@code mugsun.ai.enabled}（环境变量 {@code MUGSUN_AI_ENABLED}）设成 false 时，mugsun-boot 的 AI 模块整个不装配。
 * 单体版里这就是「关掉 AI」；放在 ai 服务里，结果是一个没有任何 AI 接口、却照样注册到注册中心的服务，
 * system 会以为 AI 可用、把菜单显示出来，点进去全是 404，OpenAI 兼容接口也全部 404。所以这里直接启动失败。
 */
@Component
class AiModuleRequired {

	static final String MESSAGE = "ai 服务里没有装配 AI 模块：配置项 mugsun.ai.enabled（环境变量 MUGSUN_AI_ENABLED）是 false。"
		+ "这个服务只提供 AI 的接口，不需要 AI 就不要部署它；要部署就删掉这个配置或改成 true";

	AiModuleRequired(ObjectProvider<AiModuleService> module) {
		if (module.getIfAvailable() == null) {
			throw new IllegalStateException(MESSAGE);
		}
	}
}
