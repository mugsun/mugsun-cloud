package com.mugsun.cloud.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 限流拒绝请求时记的日志：第一次马上记，之后每 60 秒一条汇总，没有拒绝了就不再记。时间是假的，不用真的等。
 */
@ExtendWith(OutputCaptureExtension.class)
class RejectionReportTest {

	private static final String RULE = "默认=3/60s";

	private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
	private final RejectionReport report = new RejectionReport(scheduler);

	@Test
	void firstRejectionIsLoggedAtOnce(CapturedOutput output) {
		report.rejected(RULE, "192.0.2.1", () -> "GET /ping，请求编号 t-1");

		assertThat(lines(output, "开始限流")).singleElement().asString()
			.contains(" WARN ")
			.contains("开始限流：规则 默认=3/60s，客户端 192.0.2.1，GET /ping，请求编号 t-1。");
	}

	@Test
	void followingRejectionsAreSummarisedOncePerInterval(CapturedOutput output) {
		reject("192.0.2.1", 41);
		reject("192.0.2.2", 7);

		// 到这里只有第一次拒绝的那一条
		scheduler.advanceTimeBy(Duration.ofSeconds(59));
		assertThat(lines(output, "限流")).hasSize(1);

		scheduler.advanceTimeBy(Duration.ofSeconds(1));
		assertThat(lines(output, "限流还在继续")).singleElement().asString()
			.contains(" WARN ")
			.contains("过去 60 秒又拒绝了 47 个请求，来自 2 个地址；最多的是 192.0.2.1（约 40 个，规则 默认=3/60s）");
	}

	@Test
	void everyIntervalReportsOnlyItsOwnRejections(CapturedOutput output) {
		reject("192.0.2.1", 11);
		scheduler.advanceTimeBy(Duration.ofSeconds(60));
		reject("192.0.2.2", 3);
		scheduler.advanceTimeBy(Duration.ofSeconds(60));

		List<String> summaries = lines(output, "限流还在继续");
		assertThat(summaries).hasSize(2);
		assertThat(summaries.get(0)).contains("又拒绝了 10 个请求，来自 1 个地址；最多的是 192.0.2.1（约 10 个");
		assertThat(summaries.get(1)).contains("又拒绝了 3 个请求，来自 1 个地址；最多的是 192.0.2.2（约 3 个");
	}

	@Test
	void goesQuietWhenRejectionsStopAndStartsOverNextTime(CapturedOutput output) {
		reject("192.0.2.1", 5);
		scheduler.advanceTimeBy(Duration.ofMinutes(30));

		// 第一个 60 秒报了那 4 个，之后没有拒绝，不再记
		assertThat(lines(output, "开始限流")).hasSize(1);
		assertThat(lines(output, "限流还在继续")).hasSize(1);

		report.rejected("/system/auth=30/60s", "192.0.2.9", () -> "POST /system/auth/login，请求编号 t-2");
		assertThat(lines(output, "开始限流")).hasSize(2);
		assertThat(lines(output, "开始限流").get(1)).contains("规则 /system/auth=30/60s，客户端 192.0.2.9，POST /system/auth/login");
	}

	@Test
	void singleRejectionIsNotReportedTwice(CapturedOutput output) {
		reject("192.0.2.1", 1);
		scheduler.advanceTimeBy(Duration.ofMinutes(5));

		assertThat(lines(output, "限流")).hasSize(1);
	}

	@Test
	void detailIsOnlyBuiltForTheFirstLine() {
		AtomicInteger built = new AtomicInteger();

		for (int i = 0; i < 20; i++) {
			report.rejected(RULE, "192.0.2.1", () -> "GET /ping " + built.incrementAndGet());
		}

		assertThat(built).hasValue(1);
	}

	@Test
	void latestRuleOfTheBusiestClientIsShown(CapturedOutput output) {
		reject("192.0.2.1", 1);
		report.rejected(RULE, "192.0.2.7", () -> "");
		report.rejected("/v1=60/60s", "192.0.2.7", () -> "");
		scheduler.advanceTimeBy(Duration.ofSeconds(60));

		assertThat(lines(output, "限流还在继续")).singleElement().asString()
			.contains("最多的是 192.0.2.7（约 2 个，规则 /v1=60/60s）");
	}

	@Test
	void onlySomeClientsAreCountedOneByOne(CapturedOutput output) {
		reject("first", 1);
		for (int i = 0; i < RejectionReport.MAX_TRACKED_CLIENTS + 500; i++) {
			reject("client-" + i, 1);
		}
		reject("client-7", 5);
		scheduler.advanceTimeBy(Duration.ofSeconds(60));

		assertThat(lines(output, "限流还在继续")).singleElement().asString()
			.contains("又拒绝了 1505 个请求，来自至少 1000 个地址；最多的是 client-7（约 6 个");
	}

	@Test
	void stoppedSchedulerDoesNotBreakTheRequest(CapturedOutput output) {
		scheduler.dispose();

		assertThatCode(() -> reject("192.0.2.1", 3)).doesNotThrowAnyException();
		// 约不上过一会儿再报，就每次都当成第一次
		assertThat(lines(output, "开始限流")).hasSize(3);
	}

	private void reject(String client, int times) {
		for (int i = 0; i < times; i++) {
			report.rejected(RULE, client, () -> "GET /ping，请求编号 t");
		}
	}

	private static List<String> lines(CapturedOutput output, String text) {
		return output.getAll().lines().filter(line -> line.contains(text)).toList();
	}
}
