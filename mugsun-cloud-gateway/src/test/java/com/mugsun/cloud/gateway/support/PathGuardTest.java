package com.mugsun.cloud.gateway.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内部接口路径的识别：正常路径放行；/inner 开头的路径不管怎么换写法（编码、大小写、多余的斜杠、分号参数）都要认出来；
 * 带上级目录段或控制字符的路径直接判为不合法。
 */
class PathGuardTest {

	private final PathGuard guard = new PathGuard("/inner");

	@ParameterizedTest
	@ValueSource(strings = {"", "/", "/ping", "/system/user/list", "/system/inner/list", "/innerx/list",
		"/inner-api/list", "/file/100%25.png", "/file/%E6%8A%A5%E8%A1%A8.xlsx", "/a/..b/c", "/a/.../c", "/a/b."})
	void ordinaryPathsPass(String path) {
		assertThat(guard.inspect(path)).isEqualTo(PathGuard.Verdict.PASS);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/inner", "/inner/", "/inner/message/send", "inner/x"})
	void innerPathsAreRecognized(String path) {
		assertThat(guard.inspect(path)).isEqualTo(PathGuard.Verdict.INNER);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"//inner/x", "/./inner/x", "/INNER/x", "/Inner/x",
		"/%69nner/x", "/%49NNER/x", "/%2569nner/x", "/%252569nner/x",
		"/inner;v=1/x", "/;a=b/inner/x", "/inner%3Bv=1/x",
		"\\inner\\x", "/%5Cinner/x", "/%2Finner/x", "/%2e/inner/x",
		"/inner%20/x", "/%20inner/x"})
	void disguisedInnerPathsAreRecognized(String path) {
		assertThat(guard.inspect(path)).isEqualTo(PathGuard.Verdict.INNER);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/..", "/a/../inner/x", "/a/%2e%2e/inner/x", "/a/%252e%252e/b", "/a/..;x=1/b",
		"/a/%00/b", "/a/%0d%0a/b", "/a%7F/b"})
	void parentSegmentsAndControlCharsAreMalformed(String path) {
		assertThat(guard.inspect(path)).isEqualTo(PathGuard.Verdict.MALFORMED);
	}

	@Test
	void innerPrefixIsConfigurable() {
		PathGuard custom = new PathGuard("/rpc/");
		assertThat(custom.inspect("/rpc/user/get")).isEqualTo(PathGuard.Verdict.INNER);
		assertThat(custom.inspect("/inner/user/get")).isEqualTo(PathGuard.Verdict.PASS);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "/", "/a/b", "/a b", "/*"})
	void invalidInnerPrefixFailsFast(String innerPath) {
		assertThatThrownBy(() -> new PathGuard(innerPath)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("mugsun.gateway.inner-path");
	}
}
