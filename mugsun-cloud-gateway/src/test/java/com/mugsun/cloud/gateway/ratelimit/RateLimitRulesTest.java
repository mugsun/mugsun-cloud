package com.mugsun.cloud.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一个请求算在哪条限流规则上：前缀最长的那条，都不沾的用默认的；换个写法躲不开单独定的限额。
 */
class RateLimitRulesTest {

	private static final String LIMIT = "mugsun.gateway.rate-limit";
	private static final String PATHS = "mugsun.gateway.rate-limit-paths";

	@Test
	void nothingConfiguredMeansNoRules() {
		RateLimitRules rules = rules("", "");

		assertThat(rules.isEmpty()).isTrue();
		assertThat(rules.ruleFor(List.of("/system/user/list"))).isNull();
		assertThat(rules(null, null).isEmpty()).isTrue();
		assertThat(rules("  ", " , ").isEmpty()).isTrue();
	}

	@Test
	void defaultLimitCoversEveryPath() {
		RateLimitRules rules = rules("600/60s", "");

		assertThat(rules.isEmpty()).isFalse();
		assertThat(rules.ruleFor(List.of("/system/user/list")).quota()).isEqualTo(new Quota(600, Duration.ofSeconds(60)));
		assertThat(rules.ruleFor(List.of("/"))).isSameAs(rules.ruleFor(List.of("/v1/chat/completions")));
		assertThat(rules.defaultRule()).hasToString("默认=600/60s");
	}

	@Test
	void pathWithItsOwnLimitDoesNotUseTheDefault() {
		RateLimitRules rules = rules("600/60s", "/system/auth=30/60s,/system/gis/tile=6000/60s");

		assertThat(rules.ruleFor(List.of("/system/auth/login"))).hasToString("/system/auth=30/60s");
		assertThat(rules.ruleFor(List.of("/system/auth"))).hasToString("/system/auth=30/60s");
		assertThat(rules.ruleFor(List.of("/system/gis/tile/3/2/1.png"))).hasToString("/system/gis/tile=6000/60s");
		assertThat(rules.ruleFor(List.of("/system/user/list"))).hasToString("默认=600/60s");
		// 前缀按整段比较
		assertThat(rules.ruleFor(List.of("/system/authx/login"))).hasToString("默认=600/60s");
		assertThat(rules.pathRuleCount()).isEqualTo(2);
	}

	@Test
	void longestPrefixWinsWhateverTheOrderWritten() {
		RateLimitRules shortFirst = rules("", "/system=100/60s,/system/auth=30/60s,/system/auth/login=5/60s");
		RateLimitRules longFirst = rules("", "/system/auth/login=5/60s,/system/auth=30/60s,/system=100/60s");

		for (RateLimitRules rules : List.of(shortFirst, longFirst)) {
			assertThat(rules.ruleFor(List.of("/system/auth/login")).quota().requests()).isEqualTo(5);
			assertThat(rules.ruleFor(List.of("/system/auth/captcha")).quota().requests()).isEqualTo(30);
			assertThat(rules.ruleFor(List.of("/system/user/list")).quota().requests()).isEqualTo(100);
			assertThat(rules.ruleFor(List.of("/track/collect"))).isNull();
		}
	}

	@Test
	void everyRuleCountsSeparately() {
		RateLimitRules rules = rules("600/60s", "/system/auth=30/60s,/v1=60/60s");

		assertThat(List.of(rules.defaultRule().index(), rules.ruleFor(List.of("/system/auth")).index(),
			rules.ruleFor(List.of("/v1/models")).index())).doesNotHaveDuplicates();
	}

	@Test
	void unlimitedTakesAPathOutOfTheDefaultLimit() {
		RateLimitRules rules = rules("600/60s", "/system/gis/tile=unlimited");

		RateLimitRules.Rule rule = rules.ruleFor(List.of("/system/gis/tile/3/2/1.png"));
		assertThat(rule.unlimited()).isTrue();
		assertThat(rule).hasToString("/system/gis/tile=unlimited");
		assertThat(rules.ruleFor(List.of("/system/gis/layer")).unlimited()).isFalse();
	}

	@Test
	void onlyUnlimitedPathsMeansNothingToCount() {
		assertThat(rules("", "/system/gis/tile=unlimited").isEmpty()).isTrue();
		assertThat(rules("", "/system/gis/tile=unlimited,/system/auth=30/60s").isEmpty()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {"/SYSTEM/Auth/login", "//system//auth/login", "/system;v=1/auth;x/login", "/%73ystem/auth/login",
		"/%2573ystem/auth/login", "/system%2Fauth%2Flogin", "/system/./auth/login", "/system\\auth/login"})
	void anotherSpellingOfThePathDoesNotEscapeItsLimit(String path) {
		RateLimitRules rules = rules("600/60s", "/system/auth=30/60s");

		assertThat(rules.ruleFor(List.of(path))).hasToString("/system/auth=30/60s");
	}

	@Test
	void pathAfterRouteRewritingCountsToo() {
		RateLimitRules rules = rules("600/60s", "/auth=30/60s");

		// 客户端发来的是 /sys/auth/login，路由去掉第一段后发给服务的是 /auth/login
		assertThat(rules.ruleFor(List.of("/sys/auth/login", "/auth/login"))).hasToString("/auth=30/60s");
		assertThat(rules.ruleFor(List.of("/sys/auth/login"))).hasToString("默认=600/60s");
	}

	@Test
	void prefixIsShownInLowerCaseWithoutExtraSlashes() {
		assertThat(rules("", "/System//Auth/=30/60s").ruleFor(List.of("/system/auth/login"))).hasToString("/system/auth=30/60s");
	}

	@Test
	void separatorsAreTheSameAsInOtherLists() {
		RateLimitRules rules = rules("", " /a=1/1s ; /b=2/1s\n/c=unlimited , ");

		assertThat(rules.pathRuleCount()).isEqualTo(3);
		assertThat(rules.ruleFor(List.of("/b/x")).quota().requests()).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/system/auth", "/system/auth=", "=30/60s", "30/60s", "/system/auth=30", "/system/auth=30/60d",
		"/system/auth=0/60s", "/system/auth=UNLIMITED", "/system/auth=none", "system/auth=30/60s", "/=30/60s",
		"/system/*=30/60s", "/system/../auth=30/60s", "/a=b=30/60s", "/system/auth:30/60s"})
	void malformedPathLimitFailsFast(String item) {
		assertThatThrownBy(() -> rules("600/60s", "/v1=60/60s," + item))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(PATHS);
	}

	@Test
	void samePrefixTwiceFailsFast() {
		assertThatThrownBy(() -> rules("", "/system/auth=30/60s,/v1=60/60s,/System/Auth/=unlimited"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(PATHS)
			.hasMessageContaining("不止一次");
	}

	@Test
	void malformedDefaultLimitFailsFast() {
		assertThatThrownBy(() -> rules("600", ""))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(LIMIT);
		assertThatThrownBy(() -> rules("unlimited", "")).isInstanceOf(IllegalArgumentException.class);
	}

	private static RateLimitRules rules(String defaultLimit, String pathLimits) {
		return RateLimitRules.parse(defaultLimit, LIMIT, pathLimits, PATHS);
	}
}
