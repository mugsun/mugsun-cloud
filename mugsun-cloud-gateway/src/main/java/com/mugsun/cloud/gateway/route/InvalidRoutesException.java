package com.mugsun.cloud.gateway.route;

import com.mugsun.cloud.config.ConfigText;

/**
 * 一份路由配置不合格。消息是给运维看的，会写进日志：只说哪里不对，不带配置里的值。
 */
class InvalidRoutesException extends RuntimeException {

	InvalidRoutesException(String message) {
		super(message);
	}

	/** 配置里的名字（路由 id、断言名、配置项的名字）写进消息以前先过一遍，见 {@link ConfigText#name} */
	static String name(String raw) {
		return ConfigText.name(raw);
	}
}
