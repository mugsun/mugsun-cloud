package com.mugsun.cloud.gateway.lane;

import com.mugsun.cloud.gateway.lane.LaneRuleBook.Outcome;
import com.mugsun.cloud.gateway.lane.LaneRuleBook.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正在用的那份灰度规则：新来的配置合格才换上，不合格就整份不用、原来的不动；空的配置表示不要规则；
 * 每收到一份和上一次不一样的配置记一行日志。
 */
@ExtendWith(OutputCaptureExtension.class)
class LaneRuleBookTest {

	private static final String BY_ADDRESS = "lanes:\n  - name: canary\n    client-ips: 203.0.113.7\n";
	private static final String BROKEN = "lanes:\n  - name: canary\n    client-ips: 198.51.100.77\n    precent: 5\n";

	private final LaneRuleBook book = new LaneRuleBook();

	private static List<String> linesAboutRules(CapturedOutput output) {
		return output.getOut().lines().filter(line -> line.contains("灰度规则")).toList();
	}

	@Test
	void startsWithoutRules() {
		assertThat(book.hasRules()).isFalse();
		assertThat(book.laneFor("203.0.113.7", "www.example.com", "canary")).isNull();
		assertThat(book.current()).isEqualTo(LaneRules.NONE);
	}

	@Test
	void goodDocumentIsPutToUse(CapturedOutput output) {
		Outcome outcome = book.load(BY_ADDRESS);

		assertThat(outcome).isEqualTo(new Outcome(Status.UPDATED, 1, null));
		assertThat(book.hasRules()).isTrue();
		assertThat(book.laneFor("203.0.113.7", "www.example.com", null)).isEqualTo("canary");
		assertThat(book.laneFor("203.0.113.8", "www.example.com", null)).isNull();
		assertThat(linesAboutRules(output)).singleElement().asString()
			.contains(" INFO ")
			.endsWith("灰度规则已更新，现在是：1 个灰度标记：canary（地址 1 段）");
	}

	/** 启动时读一次，监听又把同一份送来一次：第二次什么都不用做，也不重复记日志 */
	@Test
	void sameDocumentTwiceInARowIsLoggedOnce(CapturedOutput output) {
		book.load(BY_ADDRESS);

		assertThat(book.load(BY_ADDRESS)).isEqualTo(new Outcome(Status.UNCHANGED, 1, null));

		assertThat(linesAboutRules(output)).hasSize(1);
	}

	/**
	 * 改了配置、意思没变（加了注释，换了写法）：规则不用换，但要记一行。
	 * 改配置的人靠日志里有没有新的一行判断改动送到没有，这里不记，他会以为没送到。
	 */
	@Test
	void differentDocumentWithTheSameMeaningChangesNothingButIsLogged(CapturedOutput output) {
		book.load(BY_ADDRESS);

		Outcome outcome = book.load("# 改了注释\nlanes:\n  - name: canary\n    client-ips: [203.0.113.7]\n");

		assertThat(outcome).isEqualTo(new Outcome(Status.UNCHANGED, 1, null));
		assertThat(linesAboutRules(output)).hasSize(2).last().asString()
			.contains(" INFO ")
			.endsWith("灰度规则不用换：新收到的配置和现在的一样（1 个灰度标记：canary（地址 1 段））");
	}

	/** 写错了一份没被采用，再改回原样：规则没变，日志里要有新的一行，不然最后一行还是那条警告 */
	@Test
	void puttingTheOldDocumentBackAfterABrokenOneIsLogged(CapturedOutput output) {
		book.load(BY_ADDRESS);
		book.load(BROKEN);

		assertThat(book.load(BY_ADDRESS)).isEqualTo(new Outcome(Status.UNCHANGED, 1, null));

		assertThat(linesAboutRules(output)).hasSize(3).last().asString()
			.contains(" INFO ")
			.endsWith("灰度规则不用换：新收到的配置和现在的一样（1 个灰度标记：canary（地址 1 段））");
	}

	/** 同一份写错的配置接连送来两次：结果一样，警告只记一次 */
	@Test
	void sameBrokenDocumentTwiceInARowIsWarnedAboutOnce(CapturedOutput output) {
		book.load(BROKEN);

		Outcome again = book.load(BROKEN);

		assertThat(again.status()).isEqualTo(Status.REJECTED);
		assertThat(again.problem()).startsWith("有不认识的配置项：lanes[0].precent");
		assertThat(linesAboutRules(output)).hasSize(1);
	}

	/** Nacos 里没有这份配置时，启动读到的是空：本来就没有规则，没有东西要记 */
	@Test
	void nothingAtTheStartIsNotLogged(CapturedOutput output) {
		assertThat(book.load(null)).isEqualTo(new Outcome(Status.UNCHANGED, 0, null));

		assertThat(linesAboutRules(output)).isEmpty();
	}

	/** 改错了的配置不能让正在进行的灰度停掉，也不能生效一半 */
	@Test
	void brokenDocumentKeepsTheRulesInUse(CapturedOutput output) {
		book.load(BY_ADDRESS);

		Outcome outcome = book.load(BROKEN);

		assertThat(outcome.status()).isEqualTo(Status.REJECTED);
		assertThat(outcome.lanes()).isEqualTo(1);
		assertThat(outcome.problem()).startsWith("有不认识的配置项：lanes[0].precent");
		assertThat(book.laneFor("203.0.113.7", "www.example.com", null)).isEqualTo("canary");
		assertThat(book.laneFor("198.51.100.77", "www.example.com", null)).isNull();
		assertThat(linesAboutRules(output)).hasSize(2).last().asString()
			.contains(" WARN ")
			.contains("灰度规则没有更新，继续用原来的（1 个灰度标记：canary（地址 1 段））。原因：有不认识的配置项：lanes[0].precent");
	}

	@Test
	void brokenDocumentAtTheStartLeavesNoRules(CapturedOutput output) {
		Outcome outcome = book.load(BROKEN);

		assertThat(outcome.status()).isEqualTo(Status.REJECTED);
		assertThat(outcome.lanes()).isZero();
		assertThat(book.hasRules()).isFalse();
		assertThat(linesAboutRules(output)).singleElement().asString()
			.contains("灰度规则没有更新，继续用原来的（没有灰度规则）。原因：");
	}

	/** 把 Nacos 里的配置删掉或清空，就是停掉灰度：这是出事时最快的退路，要立刻生效 */
	@Test
	void emptyDocumentClearsTheRules(CapturedOutput output) {
		book.load(BY_ADDRESS);

		assertThat(book.load(null)).isEqualTo(new Outcome(Status.UPDATED, 0, null));

		assertThat(book.hasRules()).isFalse();
		assertThat(book.laneFor("203.0.113.7", "www.example.com", null)).isNull();
		assertThat(linesAboutRules(output)).hasSize(2).last().asString()
			.contains(" INFO ")
			.contains("灰度规则已清空");
		// 已经是空的，再来一份写法不同的空配置：规则不用换，各记一行
		assertThat(book.load("  \n").status()).isEqualTo(Status.UNCHANGED);
		assertThat(book.load("lanes: []\n").status()).isEqualTo(Status.UNCHANGED);
		assertThat(linesAboutRules(output)).hasSize(4).last().asString()
			.endsWith("灰度规则不用换：新收到的配置和现在的一样（没有灰度规则）");
	}

	@Test
	void newRulesReplaceTheOldOnesAsAWhole() {
		book.load(BY_ADDRESS);

		Outcome outcome = book.load("""
			lanes:
			  - name: v2
			    hosts: beta.example.com
			  - name: canary
			    percent: 0
			""");

		assertThat(outcome).isEqualTo(new Outcome(Status.UPDATED, 2, null));
		assertThat(book.laneFor("203.0.113.7", "www.example.com", null)).isNull();
		assertThat(book.laneFor("203.0.113.7", "beta.example.com", null)).isEqualTo("v2");
	}

	/** 规则里的地址和域名不进日志：换上的、没收的都一样 */
	@Test
	void addressesAndHostsStayOutOfTheLog(CapturedOutput output) {
		book.load("lanes:\n  - name: canary\n    client-ips: 192.0.2.44\n    hosts: staging.example.org\n");
		book.load(BROKEN);
		book.load("lanes:\n  - name: canary\n    client-ips: 198.51.100.77, not-an-address\n");

		assertThat(linesAboutRules(output)).hasSize(3);
		assertThat(output.getAll()).doesNotContain("192.0.2.44").doesNotContain("staging.example.org")
			.doesNotContain("198.51.100.77").doesNotContain("not-an-address");
	}
}
