package com.mugsun.cloud.gateway.lane;

/**
 * 一份灰度规则不合格。消息是给运维看的，会写进日志：只说哪里不对，不带配置里的地址和域名。
 */
class InvalidLaneRulesException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	InvalidLaneRulesException(String message) {
		super(message);
	}
}
