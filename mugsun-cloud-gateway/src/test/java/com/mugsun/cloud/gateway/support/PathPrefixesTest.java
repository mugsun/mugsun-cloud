package com.mugsun.cloud.gateway.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路径是不是落在某个前缀下面：正常写法按整段比较；换了写法（编码、大小写、多余的斜杠、分号参数）也要认出来；
 * 前缀本身写错时报错，不忽略。
 */
class PathPrefixesTest {

	private final PathPrefixes prefixes = PathPrefixes.parse("/actuator, /v3/api-docs", "test.paths");

	@ParameterizedTest
	@ValueSource(strings = {"/actuator", "/actuator/", "/actuator/health", "/actuator/metrics/jvm.memory.used",
		"/v3/api-docs", "/v3/api-docs/swagger-config", "actuator/health"})
	void pathsUnderAPrefixAreContained(String path) {
		assertThat(prefixes.contains(path)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "/", "/ping", "/actuatorx", "/actuator-x/health", "/actuator.json", "/system/actuator",
		"/system/actuator/health", "/v3", "/v3/api", "/v3/api-docsx", "/v3/x/api-docs", "/file/100%25.png",
		"/file/%E6%8A%A5%E8%A1%A8.xlsx", "/a/..b/c"})
	void otherPathsAreNot(String path) {
		assertThat(prefixes.contains(path)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"//actuator/health", "/./actuator/health", "/ACTUATOR/health", "/Actuator",
		"/%61ctuator/health", "/%41CTUATOR", "/%2561ctuator/health", "/%252561ctuator/health",
		"/actuator;v=1/health", "/;a=b/actuator/health", "/actuator%3Bv=1/health",
		"\\actuator\\health", "/%5Cactuator/health", "/%2Factuator/health", "/actuator%2Fhealth", "/%2e/actuator",
		"/actuator%20/health", "/%20actuator/health",
		"/V3//API-DOCS", "/v3/./api-docs", "/v3;x=1/api-docs;y=2/z", "/v3%2Fapi-docs", "/v3/api%2Ddocs"})
	void disguisedPathsAreContained(String path) {
		assertThat(prefixes.contains(path)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"/a/../actuator", "/a/..", "/x/%2e%2e/y", "/x/%252e%252e/y", "/x/..;v=1/y"})
	void pathsWithParentSegmentsCountAsContained(String path) {
		assertThat(prefixes.contains(path)).isTrue();
	}

	@Test
	void nothingConfiguredContainsNothing() {
		for (String text : new String[] {null, "", "  ", " , "}) {
			PathPrefixes empty = PathPrefixes.parse(text, "test.paths");
			assertThat(empty.isEmpty()).isTrue();
			assertThat(empty.contains("/actuator")).isFalse();
			assertThat(empty.contains("/a/../b")).isFalse();
		}
	}

	@Test
	void prefixesAreSeparatedByCommasSemicolonsOrSpaces() {
		PathPrefixes parsed = PathPrefixes.parse("/a,/b;/c /d,\n/e/", "test.paths");
		assertThat(parsed.size()).isEqualTo(5);
		assertThat(parsed.contains("/e/x")).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"actuator", "/", "//", "/a/../b", "/a/./b", "/a/*", "/a/**", "/路径", "/a%20b", "/a?x=1",
		"/ok,bad"})
	void invalidPrefixFailsFast(String text) {
		assertThatThrownBy(() -> PathPrefixes.parse(text, "test.paths")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("test.paths");
	}
}
