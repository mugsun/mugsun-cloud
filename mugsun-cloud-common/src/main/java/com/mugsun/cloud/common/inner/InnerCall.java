package com.mugsun.cloud.common.inner;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次已经验签通过的内部调用。
 *
 * @param caller 调用方的服务名。所有服务共用一把密钥，所以它只说明「调用方自称是谁」，用来记日志，不能拿来做授权判断
 * @param tenant 调用方那边生效的租户编号；{@code -1} 表示不按租户过滤（平台管理员切到了「全部租户」）
 * @param body   请求体；调用方没传时是一个空对象
 */
public record InnerCall(String caller, String tenant, JsonNode body) {
}
