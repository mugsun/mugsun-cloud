package com.mugsun.cloud.gis;

import com.mugsun.boot.gis.GisModuleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * gis 服务启动时确认 GIS 模块确实装配上了。
 *
 * <p>{@code mugsun.gis.enabled}（环境变量 {@code MUGSUN_GIS_ENABLED}）设成 false 时，mugsun-boot 的 GIS 模块整个不装配。
 * 单体版里这就是「关掉 GIS」；放在 gis 服务里，结果是一个没有任何 GIS 接口、却照样注册到注册中心的服务，
 * system 会以为 GIS 可用、把菜单显示出来，点进去全是 404。所以这里直接启动失败。
 */
@Component
class GisModuleRequired {

	static final String MESSAGE = "gis 服务里没有装配 GIS 模块：配置项 mugsun.gis.enabled（环境变量 MUGSUN_GIS_ENABLED）是 false。"
		+ "这个服务只提供 GIS 接口，不需要 GIS 就不要部署它；要部署就删掉这个配置或改成 true。"
		+ "想在运行时临时关闭 GIS，用系统参数 gis.module.enabled";

	GisModuleRequired(ObjectProvider<GisModuleService> module) {
		if (module.getIfAvailable() == null) {
			throw new IllegalStateException(MESSAGE);
		}
	}
}
