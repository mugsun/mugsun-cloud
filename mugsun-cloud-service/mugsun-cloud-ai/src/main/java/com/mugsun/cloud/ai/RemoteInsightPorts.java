package com.mugsun.cloud.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.module.ModuleInsightPort;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.cloud.common.inner.InnerPaths;
import com.mugsun.cloud.common.inner.ServiceNames;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 「AI 问图层」「AI 解读埋点数据」要的库内实数，分别向 gis、track 服务取。
 *
 * <p>两个实现一直都注册着，对方服务在不在等到用的时候再看：不在就告诉用户那个服务现在不在线、查不了，
 * 效果和单体版没装那个模块时一样。服务可以随时加上或撤掉，ai 服务不用跟着重启。
 */
@Configuration(proxyBeanMethods = false)
class RemoteInsightPorts {

	@Bean
	ModuleInsightPort gisInsightPort(ServiceNames names, InnerClient client, ObjectMapper objectMapper) {
		return new RemoteInsightPort("gis", names.gis(), InnerPaths.GIS_INSIGHT_SNAPSHOT, "地理信息", "图层", client,
			objectMapper);
	}

	@Bean
	ModuleInsightPort trackInsightPort(ServiceNames names, InnerClient client, ObjectMapper objectMapper) {
		return new RemoteInsightPort("track", names.track(), InnerPaths.TRACK_INSIGHT_SNAPSHOT, "埋点", "埋点数据", client,
			objectMapper);
	}
}
