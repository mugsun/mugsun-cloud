package com.mugsun.cloud.system.module;

import com.mugsun.boot.common.module.GisModuleStatus;
import com.mugsun.boot.common.module.InvalidModuleSwitchException;
import com.mugsun.boot.common.module.ModuleSwitch;
import com.mugsun.boot.common.module.ModuleSwitchRuntime;
import com.mugsun.boot.system.service.ParamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * system 服务看到的 GIS 模块状态：菜单里显不显示 GIS、登录信息里的 {@code gisEnabled} 都问它。
 *
 * <p>单体版里这个接口由 GIS 模块自己实现，「在不在」看模块的 jar 有没有打进包里。微服务版里 GIS 是另一个服务，
 * 改成看注册中心里有没有 gis 服务的实例。其余两个开关和单体版是同一个、规则也一样：
 * 配置项 {@code mugsun.gis.enabled}（环境变量 {@code MUGSUN_GIS_ENABLED}）不设按开，只认 true/false，其它值启动失败；
 * 系统参数 {@code gis.module.enabled} 可以在运行时关掉。
 *
 * <p>有了这个 Bean，mugsun-boot 的 {@code OptionalModuleConfiguration} 就不再提供「模块不在」的默认实现。
 */
@Component
public class CloudGisModuleStatus implements GisModuleStatus {

	static final String YAML_KEY = "mugsun.gis.enabled";

	static final String SERVICE_ID_KEY = "mugsun.cloud.module.gis.service-id";

	static final String DEFAULT_SERVICE_ID = "mugsun-gis";

	private final ServicePresence presence;

	private final ParamService paramService;

	private final String serviceId;

	private final boolean switchedOn;

	@Autowired
	public CloudGisModuleStatus(ServicePresence presence, ParamService paramService, Environment environment) {
		this(presence, paramService, environment.getProperty(SERVICE_ID_KEY, DEFAULT_SERVICE_ID),
			environment.getProperty(YAML_KEY));
	}

	CloudGisModuleStatus(ServicePresence presence, ParamService paramService, String serviceId, String yamlRaw) {
		ModuleSwitch.State state = ModuleSwitch.parseStrict(yamlRaw);
		if (!state.legal()) {
			throw new InvalidModuleSwitchException(YAML_KEY, yamlRaw);
		}
		this.presence = presence;
		this.paramService = paramService;
		this.serviceId = serviceId;
		this.switchedOn = state.enabled();
		if (switchedOn) {
			presence.watch(serviceId);
		}
	}

	@Override
	public boolean present() {
		return switchedOn && presence.present(serviceId);
	}

	@Override
	public boolean enabled() {
		return present() && ModuleSwitchRuntime.shared()
			.enabled(ModuleSwitch.PARAM_GIS, paramService.getValue(ModuleSwitch.PARAM_GIS));
	}
}
