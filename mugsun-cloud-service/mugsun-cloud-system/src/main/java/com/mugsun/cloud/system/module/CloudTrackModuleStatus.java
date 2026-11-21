package com.mugsun.cloud.system.module;

import com.mugsun.boot.common.module.InvalidModuleSwitchException;
import com.mugsun.boot.common.module.ModuleSwitch;
import com.mugsun.boot.common.module.TrackModuleStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * system 服务看到的埋点模块状态：菜单里显不显示埋点分析、登录信息里的 {@code trackEnabled} 都问它。
 *
 * <p>单体版里这个接口由埋点模块自己实现，「在不在」看模块的 jar 有没有打进包里。微服务版里埋点是另一个服务，
 * 改成看注册中心里有没有 track 服务的实例。配置项 {@code mugsun.track.enabled}（环境变量 {@code MUGSUN_TRACK_ENABLED}）
 * 和单体版是同一个、规则也一样：不设按开，只认 true/false，其它值启动失败。
 *
 * <p>埋点没有运行时的系统参数开关，单体版也没有，这一点和 GIS 不同。
 *
 * <p>有了这个 Bean，mugsun-boot 的 {@code OptionalModuleConfiguration} 就不再提供「模块不在」的默认实现。
 */
@Component
public class CloudTrackModuleStatus implements TrackModuleStatus {

	static final String YAML_KEY = "mugsun.track.enabled";

	static final String SERVICE_ID_KEY = "mugsun.cloud.module.track.service-id";

	static final String DEFAULT_SERVICE_ID = "mugsun-track";

	private final ServicePresence presence;

	private final String serviceId;

	private final boolean switchedOn;

	@Autowired
	public CloudTrackModuleStatus(ServicePresence presence, Environment environment) {
		this(presence, environment.getProperty(SERVICE_ID_KEY, DEFAULT_SERVICE_ID), environment.getProperty(YAML_KEY));
	}

	CloudTrackModuleStatus(ServicePresence presence, String serviceId, String yamlRaw) {
		ModuleSwitch.State state = ModuleSwitch.parseStrict(yamlRaw);
		if (!state.legal()) {
			throw new InvalidModuleSwitchException(YAML_KEY, yamlRaw);
		}
		this.presence = presence;
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
		return present();
	}
}
