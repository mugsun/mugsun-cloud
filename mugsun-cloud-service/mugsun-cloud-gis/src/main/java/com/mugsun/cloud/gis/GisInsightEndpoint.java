package com.mugsun.cloud.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.module.ModuleInsightPort;
import com.mugsun.cloud.common.inner.InnerPaths;
import com.mugsun.cloud.common.insight.LocalInsightEndpoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 内部接口：图层的库内实数，给 ai 服务的「AI 问图层」用。取数用的是 mugsun-boot 的 {@code GisInsightAdapter}。
 */
@Component
class GisInsightEndpoint extends LocalInsightEndpoint {

	static final String MODULE = "gis";

	/** 和单体版没装 GIS 模块时的提示一样 */
	static final String MISSING = "地理信息模块未装配，无法查询图层";

	GisInsightEndpoint(ObjectProvider<ModuleInsightPort> ports, ObjectMapper objectMapper) {
		super(InnerPaths.GIS_INSIGHT_SNAPSHOT, MODULE, MISSING, ports, objectMapper);
	}
}
