package com.mugsun.cloud.config;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.discovery.NacosDiscoveryAutoConfiguration;
import com.alibaba.nacos.api.NacosFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * 路由、灰度规则这类「整份拿来自己解析」的配置，一个进程里只由 {@link NacosConfigSource} 这一个入口读。要读哪几份，由各功能
 * （比如网关的动态路由、灰度规则）在自己的开关打开时各登记一个 {@link NacosConfigSource.Watch}；一个都没有登记时，
 * 它什么都不做，也不去连 Nacos。
 *
 * <p>application.yml 里那种配置项不归它管：那是配置中心，由 Spring Cloud Alibaba 的 Nacos Config 组件在启动时读，
 * 读哪几份写在网关和各服务 application.yml 的 {@code spring.config.import} 里。
 *
 * <p>没有启用 Nacos 时不创建。看的是有没有 Nacos 的地址配置这个 Bean，不看某一个开关：关掉 Nacos 的开关有两个
 * （{@code spring.cloud.nacos.discovery.enabled} 和 {@code spring.cloud.discovery.enabled}），只认前一个的话，
 * 用后一个关掉时这里照样创建，又拿不到地址，启动会失败。
 *
 * <p>这个类不在网关和各服务扫描的包里，靠 Spring Boot 的自动配置生效：登记在本模块的
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 里，依赖了本模块就有。
 */
@AutoConfiguration(after = NacosDiscoveryAutoConfiguration.class)
@ConditionalOnBean(NacosDiscoveryProperties.class)
public class NacosConfigSourceConfiguration {

	/** 用服务注册的那套 Nacos 地址、账号和命名空间去读配置，不另设一套 */
	@Bean
	NacosConfigSource nacosConfigSource(NacosDiscoveryProperties nacos, ObjectProvider<NacosConfigSource.Watch> watches) {
		return new NacosConfigSource(() -> NacosFactory.createConfigService(nacos.getNacosProperties()),
			watches.orderedStream().toList());
	}
}
