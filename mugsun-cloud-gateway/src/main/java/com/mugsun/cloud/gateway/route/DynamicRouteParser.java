package com.mugsun.cloud.gateway.route;

import com.mugsun.cloud.gateway.support.ConfigYaml;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.util.List;
import java.util.Properties;

/**
 * 把一份路由配置的文本读成路由定义。写法和 application.yml 里的路由一样，最外层是 {@code routes}：
 *
 * <pre>
 * routes:
 *   - id: report
 *     uri: lb://mugsun-report
 *     predicates:
 *       - Path=/report/**
 *     filters:
 *       - StripPrefix=1
 * </pre>
 *
 * <p>不认识的配置项一律算错，不悄悄忽略：把 routes 写成 route 要是被当成「没有路由」，现有的动态路由就全没了。
 * YAML 怎么读见 {@link ConfigYaml}。
 */
final class DynamicRouteParser {

	private static final String ROOT = "routes";

	private DynamicRouteParser() {
	}

	/**
	 * @param content 配置的全文；空的（没有这份配置、只有注释、routes 下面什么都没有）表示一条路由也没有
	 * @throws InvalidRoutesException 读不出来，或者有不认识的配置项
	 */
	static List<RouteDefinition> parse(String content) {
		if (content == null || content.isBlank()) {
			return List.of();
		}
		Properties flat;
		try {
			flat = ConfigYaml.flatten(content);
		} catch (ConfigYaml.NotYamlException e) {
			throw new InvalidRoutesException("不是合法的 YAML：" + e.getMessage());
		}
		// 要看所有的键：stringPropertyNames() 不包括值是数字或布尔的那些
		for (Object name : flat.keySet()) {
			String key = String.valueOf(name);
			if (!key.equals(ROOT) && !key.startsWith(ROOT + "[")) {
				throw new InvalidRoutesException("最外层只能有 routes 这一项，路由列表写在它下面");
			}
		}
		try {
			return new Binder(new MapConfigurationPropertySource(flat))
				.bind(ROOT, Bindable.listOf(RouteDefinition.class), new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
				.orElseGet(List::of);
		} catch (BindException e) {
			throw new InvalidRoutesException(describe(e));
		}
	}

	private static String describe(BindException error) {
		String unknown = ConfigYaml.unknownNames(error);
		if (unknown != null) {
			return "有不认识的配置项：" + unknown + "。一条路由里能写的只有 id、uri、predicates、filters、order";
		}
		return "配置项 " + InvalidRoutesException.name(error.getName().toString()) + " 的值写得不对";
	}
}
