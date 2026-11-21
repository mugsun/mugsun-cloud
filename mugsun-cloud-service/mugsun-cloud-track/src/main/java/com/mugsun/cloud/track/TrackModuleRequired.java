package com.mugsun.cloud.track;

import com.mugsun.boot.track.TrackModuleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * track 服务启动时确认埋点模块确实装配上了。
 *
 * <p>{@code mugsun.track.enabled}（环境变量 {@code MUGSUN_TRACK_ENABLED}）设成 false 时，mugsun-boot 的埋点模块整个不装配。
 * 单体版里这就是「关掉埋点」；放在 track 服务里，结果是一个没有任何埋点接口、却照样注册到注册中心的服务，
 * system 会以为埋点可用、把菜单显示出来，点进去全是 404，SDK 的上报也全部 404。所以这里直接启动失败。
 */
@Component
class TrackModuleRequired {

	static final String MESSAGE = "track 服务里没有装配埋点模块：配置项 mugsun.track.enabled（环境变量 MUGSUN_TRACK_ENABLED）是 false。"
		+ "这个服务只提供埋点的接口，不需要埋点就不要部署它；要部署就删掉这个配置或改成 true";

	TrackModuleRequired(ObjectProvider<TrackModuleService> module) {
		if (module.getIfAvailable() == null) {
			throw new IllegalStateException(MESSAGE);
		}
	}
}
