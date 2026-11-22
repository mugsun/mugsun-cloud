package com.mugsun.cloud.develop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关转给代码生成服务的路径，要和这个服务里的接口类对得上。
 *
 * <p>这些接口的路径和别的 {@code /system/**} 一样，在网关里本来都归兜底的 system；这个服务有哪几个接口类，
 * 是 {@code scripts/sync-rules.json} 的 {@code core.services} 定的。规则里多分来一个接口类、网关的路由没跟着加时，
 * 哪里都不报错：请求落到 system 上，那边已经没有这个接口，得到 404。反过来，路由里多写了一条，请求被送到这边，
 * 也是 404。
 *
 * <p>直接读网关源码目录里的配置文件：这个模块不依赖网关。
 */
class DevelopRoutesTest {

	private static final String ROUTE = "mugsun-develop";

	/** 兜底的路由：按顺序匹配，代码生成服务的那一条要排在它前面 */
	private static final String FALLBACK = "mugsun-system";

	@Test
	void gatewayRoutesExactlyTheControllersOfThisService() throws Exception {
		Set<String> served = new TreeSet<>();
		for (Class<?> controller : controllers()) {
			RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
			assertThat(mapping).as("%s 类上要有 @RequestMapping：网关按它写的路径前缀转发", controller.getName()).isNotNull();
			for (String prefix : mapping.value()) {
				served.add(prefix + "/**");
			}
		}
		assertThat(served).as("这个服务里一个接口类都没找到：同步来的代码不在，或者没有编译进来").isNotEmpty();

		List<String> order = new ArrayList<>();
		Set<String> routed = new TreeSet<>();
		try (Reader reader = Files.newBufferedReader(gatewaySettings(), StandardCharsets.UTF_8)) {
			for (Object document : new Yaml().loadAll(reader)) {
				collect(document, order, routed);
			}
		}
		assertThat(routed).as("网关 application.yml 里 %s 这条路由的 Path，要和代码生成服务的接口类一一对应", ROUTE)
			.containsExactlyInAnyOrderElementsOf(served);
		assertThat(order).as("网关的路由按顺序匹配，%s 要写在兜底的 %s 前面", ROUTE, FALLBACK).contains(ROUTE, FALLBACK);
		assertThat(order.indexOf(ROUTE)).isLessThan(order.indexOf(FALLBACK));
	}

	/** 和启动类在同一个模块里的接口类。公共层也有接口类（/ping），每个服务都带着，不归网关单独转发 */
	private static List<Class<?>> controllers() throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
		URL here = DevelopApplication.class.getProtectionDomain().getCodeSource().getLocation();
		List<Class<?>> found = new ArrayList<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents("com.mugsun.boot")) {
			Class<?> type = Class.forName(candidate.getBeanClassName(), false, DevelopRoutesTest.class.getClassLoader());
			if (here.equals(type.getProtectionDomain().getCodeSource().getLocation())) {
				found.add(type);
			}
		}
		return found;
	}

	private static Path gatewaySettings() {
		Path root = Path.of("").toAbsolutePath();
		while (root != null && !Files.isDirectory(root.resolve("mugsun-cloud-gateway"))) {
			root = root.getParent();
		}
		assertThat(root).as("从当前目录往上找不到仓库根目录").isNotNull();
		return root.resolve("mugsun-cloud-gateway/src/main/resources/application.yml");
	}

	/** 在整份配置里找路由：带 id 的条目按出现的顺序记下来，代码生成服务那一条的 Path 拆开记下来 */
	private static void collect(Object node, List<String> order, Set<String> routed) {
		if (node instanceof Map<?, ?> map) {
			if (map.get("id") instanceof String id && map.get("predicates") instanceof Collection<?> predicates) {
				order.add(id);
				if (ROUTE.equals(id)) {
					for (Object predicate : predicates) {
						String text = String.valueOf(predicate);
						if (text.startsWith("Path=")) {
							routed.addAll(Arrays.asList(text.substring("Path=".length()).split(",")));
						}
					}
				}
			}
			map.values().forEach(value -> collect(value, order, routed));
		}
		else if (node instanceof Collection<?> items) {
			items.forEach(item -> collect(item, order, routed));
		}
	}
}
