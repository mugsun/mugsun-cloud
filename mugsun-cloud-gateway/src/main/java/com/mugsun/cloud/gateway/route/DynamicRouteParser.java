package com.mugsun.cloud.gateway.route;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

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
 * 用 Spring 自己的 YAML 读法，文档大小、嵌套层数、别名个数的上限都保留着。
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
		Properties flat = flatten(content);
		for (String key : flat.stringPropertyNames()) {
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

	/** YAML 读成「routes[0].id = ...」这样的一行行属性，和 Spring Boot 读 application.yml 的结果是同一种形状 */
	private static Properties flatten(String content) {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)));
		try {
			Properties flat = yaml.getObject();
			return flat == null ? new Properties() : flat;
		} catch (RuntimeException e) {
			throw new InvalidRoutesException("不是合法的 YAML：" + yamlProblem(e));
		}
	}

	/** YAML 解析器的完整报错会摘录出错那一行的原文，这里只取位置和原因 */
	private static String yamlProblem(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof MarkedYAMLException marked) {
				Mark mark = marked.getProblemMark();
				String where = mark == null ? "" : "第 " + (mark.getLine() + 1) + " 行第 " + (mark.getColumn() + 1) + " 列，";
				return where + InvalidRoutesException.text(marked.getProblem());
			}
			if (cause instanceof YAMLException) {
				return InvalidRoutesException.text(cause.getMessage());
			}
		}
		return "解析时出错（" + error.getClass().getSimpleName() + "）";
	}

	private static String describe(BindException error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof UnboundConfigurationPropertiesException unbound) {
				String names = unbound.getUnboundProperties().stream()
					.map(property -> InvalidRoutesException.name(property.getName().toString()))
					.limit(3)
					.collect(Collectors.joining("、"));
				return "有不认识的配置项：" + names + "。一条路由里能写的只有 id、uri、predicates、filters、order";
			}
		}
		return "配置项 " + InvalidRoutesException.name(error.getName().toString()) + " 的值写得不对";
	}
}
