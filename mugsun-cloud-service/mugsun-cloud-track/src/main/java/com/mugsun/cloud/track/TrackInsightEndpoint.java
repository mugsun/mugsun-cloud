package com.mugsun.cloud.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.module.ModuleInsightPort;
import com.mugsun.cloud.common.inner.InnerPaths;
import com.mugsun.cloud.common.insight.LocalInsightEndpoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 内部接口：埋点的库内实数，给 ai 服务的「AI 解读埋点数据」用。取数用的是 mugsun-boot 的 {@code TrackInsightAdapter}。
 */
@Component
class TrackInsightEndpoint extends LocalInsightEndpoint {

	static final String MODULE = "track";

	/** 和单体版没装埋点模块时的提示一样 */
	static final String MISSING = "埋点模块未装配，无法查询埋点数据";

	TrackInsightEndpoint(ObjectProvider<ModuleInsightPort> ports, ObjectMapper objectMapper) {
		super(InnerPaths.TRACK_INSIGHT_SNAPSHOT, MODULE, MISSING, ports, objectMapper);
	}
}
