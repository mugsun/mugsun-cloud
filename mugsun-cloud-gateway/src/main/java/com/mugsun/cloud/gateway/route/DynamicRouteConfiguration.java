package com.mugsun.cloud.gateway.route;

import com.alibaba.cloud.nacos.ConditionalOnNacosDiscoveryEnabled;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.support.NacosConfigSource;
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

	/** 动态路由写在 Nacos 的哪一份配置里。去读的是 {@link NacosConfigSource}，和网关要读的别的配置共用一条连接 */
	@Bean
	@ConditionalOnNacosDiscoveryEnabled
	NacosConfigSource.Watch dynamicRoutesWatch(GatewayGuardProperties properties, NacosDiscoveryProperties nacos,
			DynamicRouteLoader loader) {
		String group = properties.getDynamicRoutesGroup();
		return new NacosConfigSource.Watch("动态路由", "先只用 application.yml 里的路由", properties.getDynamicRoutesDataId(),
			group.isBlank() ? nacos.getGroup() : group, loader::load);
	}
}
