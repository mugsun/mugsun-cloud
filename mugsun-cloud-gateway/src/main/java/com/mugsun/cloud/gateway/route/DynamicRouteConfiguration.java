package com.mugsun.cloud.gateway.route;

import com.alibaba.cloud.nacos.ConditionalOnNacosDiscoveryEnabled;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.nacos.api.NacosFactory;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.validation.Validator;

import java.util.List;

/**
 * 动态路由的总开关：{@code mugsun.gateway.dynamic-routes} 不是 true 时，下面三个对象一个都不创建，
 * 网关的路由只有 application.yml 里写的那些。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("mugsun.gateway.dynamic-routes")
public class DynamicRouteConfiguration {

	@Bean
	DynamicRouteLocator dynamicRouteLocator() {
		return new DynamicRouteLocator();
	}

	@Bean
	@SuppressWarnings("rawtypes")
	DynamicRouteLoader dynamicRouteLoader(DynamicRouteLocator locator, GatewayProperties gatewayProperties,
			List<RoutePredicateFactory> predicates, List<GatewayFilterFactory> filters, BeanFactory beanFactory,
			@Qualifier("webFluxConversionService") ObjectProvider<ConversionService> conversionService,
			ObjectProvider<Validator> validator, @Value("${spring.application.name:}") String applicationName,
			ApplicationEventPublisher publisher) {
		// 参数和 Spring Cloud Gateway 自己建的那个一样，只是不注册成 bean：这样它没有事件发布器，转换动态路由时不发事件
		ConfigurationService quiet = new ConfigurationService(beanFactory, conversionService, validator);
		return new DynamicRouteLoader(locator, gatewayProperties, predicates, filters, quiet, applicationName, publisher);
	}

	/** 用服务注册的那套 Nacos 地址、账号和命名空间去读配置，不另设一套 */
	@Bean
	@ConditionalOnNacosDiscoveryEnabled
	NacosRouteSource nacosRouteSource(GatewayGuardProperties properties, NacosDiscoveryProperties nacos,
			DynamicRouteLoader loader) {
		String group = properties.getDynamicRoutesGroup();
		return new NacosRouteSource(properties.getDynamicRoutesDataId(), group.isBlank() ? nacos.getGroup() : group,
			() -> NacosFactory.createConfigService(nacos.getNacosProperties()), loader);
	}
}
