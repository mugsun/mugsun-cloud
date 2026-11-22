package com.mugsun.cloud.gateway.route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由配置的文本怎么读：写法和 application.yml 一样；不认识的东西算错；报错不带配置里的值。
 */
class DynamicRouteParserTest {

	@Test
	void readsTheSameShortNotationAsApplicationYml() {
		List<RouteDefinition> routes = DynamicRouteParser.parse("""
			# 报表服务
			routes:
			  - id: report
			    uri: lb://mugsun-report
			    predicates:
			      - Path=/report/**,/system/report/**
			      - Method=GET
			    filters:
			      - StripPrefix=1
			    order: -5
			  - id: billing
			    uri: lb://mugsun-billing
			    predicates:
			      - Path=/billing/**
			""");

		assertThat(routes).extracting(RouteDefinition::getId).containsExactly("report", "billing");
		RouteDefinition report = routes.get(0);
		assertThat(report.getUri()).hasToString("lb://mugsun-report");
		assertThat(report.getOrder()).isEqualTo(-5);
		assertThat(report.getPredicates()).extracting(PredicateDefinition::getName).containsExactly("Path", "Method");
		assertThat(report.getPredicates().get(0).getArgs().values()).containsExactly("/report/**", "/system/report/**");
		assertThat(report.getFilters()).extracting(FilterDefinition::getName).containsExactly("StripPrefix");
		assertThat(report.getFilters().get(0).getArgs().values()).containsExactly("1");
		assertThat(routes.get(1).getOrder()).isZero();
		assertThat(routes.get(1).getFilters()).isEmpty();
	}

	@Test
	void readsTheFullNotationToo() {
		List<RouteDefinition> routes = DynamicRouteParser.parse("""
			routes:
			  - id: report
			    uri: lb://mugsun-report
			    predicates:
			      - name: Path
			        args:
			          patterns: /report/**
			    filters:
			      - name: StripPrefix
			        args:
			          parts: 1
			""");

		assertThat(routes).hasSize(1);
		assertThat(routes.get(0).getPredicates().get(0).getName()).isEqualTo("Path");
		assertThat(routes.get(0).getPredicates().get(0).getArgs()).containsEntry("patterns", "/report/**");
		assertThat(routes.get(0).getFilters().get(0).getArgs()).containsEntry("parts", "1");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "  \n ", "# 只有注释\n", "routes:\n", "routes: []\n", "routes: ~\n"})
	void emptyContentMeansNoRoutes(String content) {
		assertThat(DynamicRouteParser.parse(content)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"route:\n  - id: a\n    uri: lb://a\n",
		"- id: a\n  uri: lb://a\n",
		"just some text",
		"routes:\n  - id: a\n    uri: lb://a\nspring:\n  application:\n    name: x\n",
		// 多写的那一项的值是布尔或数字时也要发现
		"routes:\n  - id: a\n    uri: lb://a\nenabled: true\n",
		"version: 2\n",
		"routes:\n  a:\n    id: a\n    uri: lb://a\n"})
	void onlyAListUnderRoutesIsAccepted(String content) {
		assertThatThrownBy(() -> DynamicRouteParser.parse(content))
			.isInstanceOf(InvalidRoutesException.class)
			.hasMessageContaining("最外层只能有 routes");
	}

	/** 把 predicates 写成 predicate：不能当成「这条路由没有条件」悄悄收下 */
	@Test
	void unknownItemInARouteIsAnErrorAndIsNamed() {
		assertThatThrownBy(() -> DynamicRouteParser.parse("""
			routes:
			  - id: a
			    uri: lb://a
			    predicate:
			      - Path=/a/**
			"""))
			.isInstanceOf(InvalidRoutesException.class)
			.hasMessageContaining("不认识的配置项")
			.hasMessageContaining("routes[0].predicate[0]");
	}

	@Test
	void wrongKindOfValueNamesTheItemButNotTheValue() {
		assertThatThrownBy(() -> DynamicRouteParser.parse("""
			routes:
			  - id: a
			    uri: lb://a
			    predicates:
			      - Path=/a/**
			    order: soon-TOP-SECRET
			"""))
			.isInstanceOf(InvalidRoutesException.class)
			.hasMessageContaining("routes[0].order")
			.hasMessageNotContaining("TOP-SECRET");
	}

	@Test
	void conditionWithoutValueIsRejected() {
		assertThatThrownBy(() -> DynamicRouteParser.parse("""
			routes:
			  - id: a
			    uri: lb://a
			    predicates:
			      - Path
			"""))
			.isInstanceOf(InvalidRoutesException.class)
			.hasMessageContaining("routes[0].predicates[0]");
	}

	@Test
	void brokenYamlGivesThePositionWithoutQuotingTheText() {
		String content = "routes:\n  - id: a\n    uri: lb://a\n    predicates: [Header=X-Key, TOP-SECRET\n";

		assertThatThrownBy(() -> DynamicRouteParser.parse(content))
			.isInstanceOf(InvalidRoutesException.class)
			.hasMessageStartingWith("不是合法的 YAML：第 ")
			.hasMessageNotContaining("TOP-SECRET")
			.hasMessageNotContaining("\n");
	}

	/** 一小段文本靠层层引用展开成极大的内容：YAML 解析器自带的上限要还在 */
	@Test
	void contentThatExpandsEndlesslyIsStopped() {
		StringBuilder content = new StringBuilder("a0: &a0 [x, x, x, x, x, x, x, x, x]\n");
		for (int level = 1; level <= 8; level++) {
			String previous = "*a" + (level - 1);
			content.append("a").append(level).append(": &a").append(level).append(" [")
				.append(String.join(", ", java.util.Collections.nCopies(9, previous))).append("]\n");
		}
		content.append("routes: *a8\n");

		assertThatThrownBy(() -> DynamicRouteParser.parse(content.toString()))
			.isInstanceOf(InvalidRoutesException.class)
			.hasMessageStartingWith("不是合法的 YAML");
	}

	/** 名字写进报错以前先整理过，整理的规矩在 ConfigTextTest 里测 */
	@Test
	void namesInMessagesAreMadeSafeForTheLog() {
		assertThat(InvalidRoutesException.name("Path")).isEqualTo("Path");
		assertThat(InvalidRoutesException.name("a\nb c")).isEqualTo("a?b?c");
	}
}
