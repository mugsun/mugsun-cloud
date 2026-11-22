package com.mugsun.cloud.gateway.support;

import com.alibaba.cloud.nacos.ConditionalOnNacosDiscoveryEnabled;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.nacos.api.NacosFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关从 Nacos 读配置的入口只有 {@link NacosConfigSource} 这一个。要读哪几份，由各功能（动态路由、灰度规则）在自己的开关打开时
 * 各登记一个 {@link NacosConfigSource.Watch}；一个都没有登记时，它什么都不做，也不去连 Nacos。没有启用 Nacos 时不创建。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnNacosDiscoveryEnabled
public class NacosConfigSourceConfiguration {

	/** 用服务注册的那套 Nacos 地址、账号和命名空间去读配置，不另设一套 */
	@Bean
	NacosConfigSource nacosConfigSource(NacosDiscoveryProperties nacos, ObjectProvider<NacosConfigSource.Watch> watches) {
		return new NacosConfigSource(() -> NacosFactory.createConfigService(nacos.getNacosProperties()),
			watches.orderedStream().toList());
	}
}
