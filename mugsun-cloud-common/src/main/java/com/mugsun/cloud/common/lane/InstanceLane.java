package com.mugsun.cloud.common.lane;

import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.alibaba.cloud.nacos.registry.NacosRegistrationCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 这个实例自己的灰度标记（{@code mugsun.cloud.lane}，环境变量 {@code MUGSUN_LANE}）。不配就是正式实例。
 *
 * <p>配了标记的实例在登记到注册中心时把标记写进元数据，网关和别的服务据此认出它是灰度实例：
 * 没有标记的请求不会发给它，只有标记相同的请求才会。写法不对时拒绝启动：写错的标记和谁都对不上，
 * 这个实例会一直收不到请求，部署的人很难发现。
 */
@Component
public class InstanceLane implements NacosRegistrationCustomizer {

	private static final Logger log = LoggerFactory.getLogger(InstanceLane.class);

	private final String name;

	public InstanceLane(@Value("${mugsun.cloud.lane:}") String configured) {
		this.name = check(configured);
	}

	static String check(String configured) {
		if (configured == null || configured.isBlank()) {
			return null;
		}
		String name = configured.strip();
		if (Lanes.BASE.equals(name)) {
			throw new IllegalStateException("mugsun.cloud.lane（环境变量 MUGSUN_LANE）不能配成 base："
				+ "这个名字表示正式实例，正式实例不用配这一项");
		}
		if (!Lanes.NAME.matcher(name).matches()) {
			throw new IllegalStateException("mugsun.cloud.lane（环境变量 MUGSUN_LANE）的写法不对：" + name
				+ "。只能用小写字母、数字和短横线，字母或数字开头，最长 32 个字符");
		}
		return name;
	}

	/** 这个实例的标记，正式实例是 null */
	public String name() {
		return name;
	}

	@Override
	public void customize(NacosRegistration registration) {
		if (name == null) {
			return;
		}
		registration.getMetadata().put(Lanes.METADATA_KEY, name);
		log.info("这个实例是灰度实例，标记 {}：没有标记的请求不会发到这里，只有网关按规则定了这个标记的请求才会", name);
	}
}
