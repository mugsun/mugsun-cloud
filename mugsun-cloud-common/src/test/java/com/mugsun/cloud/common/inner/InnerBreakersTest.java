package com.mugsun.cloud.common.inner;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InnerBreakersTest {

	private static final String SERVICE = "mugsun-gis";

	private static final String A = "10.0.0.5:8102";

	private static final String B = "10.0.0.6:8102";

	private static final String PATH = "/inner/gis/insight/snapshot";

	private static final String OTHER_PATH = "/inner/gis/layer/list";

	private static final String TENANT = "000000";

	private static final String OTHER_TENANT = "T10001";

	private static final long PAUSE = 10_000;

	/** 假的时钟，测试自己往前拨。从负数起：真实的 nanoTime 可以是负的 */
	private final AtomicLong now = new AtomicLong(-5_000);

	private final InnerBreakers breakers = new InnerBreakers(3, PAUSE, now::get);

	// ---------- 什么时候熔断 ----------

	@Test
	void opensAfterThreeFailuresInARow() {
		fail(A, PATH);
		fail(A, PATH);
		assertThat(open(A, PATH)).isFalse();

		fail(A, PATH);

		assertThat(enter(A, PATH)).isNull();
	}

	@Test
	void anAnswerInBetweenStartsTheCountAgain() {
		fail(A, PATH);
		fail(A, PATH);
		answer(A, PATH);
		fail(A, PATH);
		fail(A, PATH);

		assertThat(open(A, PATH)).isFalse();
	}

	/** 对方回「忙」是它自己在限制并发：再多次也不熔断，但也不证明它没事，不把前面的失败一笔勾销 */
	@Test
	void busyNeitherCountsNorResets() {
		for (int i = 0; i < 20; i++) {
			busy(A, PATH);
		}
		assertThat(open(A, PATH)).isFalse();

		fail(A, PATH);
		fail(A, PATH);
		busy(A, PATH);
		fail(A, PATH);

		assertThat(enter(A, PATH)).isNull();
	}

	/** 凭单上什么都没标就交回来的按失败记；同一张交两次只记一次 */
	@Test
	void aTicketCountsOnceHoweverOftenItIsClosed() {
		for (int i = 0; i < 2; i++) {
			InnerBreakers.Ticket ticket = enter(A, PATH);
			ticket.close();
			ticket.close();
			ticket.close();
		}

		assertThat(open(A, PATH)).isFalse();
	}

	// ---------- 熔断中、试探 ----------

	@Test
	void nothingIsSentUntilThePauseIsOver() {
		trip(A, PATH);

		now.addAndGet(PAUSE - 1);
		assertThat(enter(A, PATH)).isNull();

		now.addAndGet(1);
		assertThat(enter(A, PATH)).isNotNull();
	}

	@Test
	void onlyOneProbeIsOutAtATime() {
		trip(A, PATH);
		now.addAndGet(PAUSE);

		InnerBreakers.Ticket probe = enter(A, PATH);

		assertThat(probe).isNotNull();
		assertThat(enter(A, PATH)).isNull();
		now.addAndGet(PAUSE * 5);
		assertThat(enter(A, PATH)).as("试探的请求没回来之前，等多久都不再放第二个").isNull();
	}

	/** 很多线程同时来领，也只有一个领得到试探的名额 */
	@Test
	void onlyOneOfManyConcurrentCallersBecomesTheProbe() throws Exception {
		trip(A, PATH);
		now.addAndGet(PAUSE);
		int callers = 64;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<InnerBreakers.Ticket>> results = new ArrayList<>();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < callers; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return enter(A, PATH);
				}));
			}
			start.countDown();
			int granted = 0;
			for (Future<InnerBreakers.Ticket> result : results) {
				if (result.get(5, TimeUnit.SECONDS) != null) {
					granted++;
				}
			}

			assertThat(granted).isEqualTo(1);
		}
	}

	@Test
	void anAnsweredProbeRestoresNormalService() {
		trip(A, PATH);
		now.addAndGet(PAUSE);

		answer(A, PATH);

		assertThat(open(A, PATH)).isFalse();
		// 失败次数也从头算：再失败两次不会立刻又熔断
		fail(A, PATH);
		fail(A, PATH);
		assertThat(open(A, PATH)).isFalse();
	}

	@Test
	void aFailedProbeMeansAnotherFullPause() {
		trip(A, PATH);
		now.addAndGet(PAUSE + 3_000);

		fail(A, PATH);

		now.addAndGet(PAUSE - 1);
		assertThat(enter(A, PATH)).isNull();
		now.addAndGet(1);
		assertThat(enter(A, PATH)).isNotNull();
	}

	/** 试探的请求得到「忙」：对方还没缓过来，不算恢复 */
	@Test
	void aProbeToldBusyMeansAnotherFullPause() {
		trip(A, PATH);
		now.addAndGet(PAUSE);

		busy(A, PATH);

		now.addAndGet(PAUSE - 1);
		assertThat(enter(A, PATH)).isNull();
		now.addAndGet(1);
		assertThat(enter(A, PATH)).isNotNull();
	}

	// ---------- 熔断之前发出去、之后才回来的请求 ----------

	/** 不然一批同时发出去的请求陆续超时，会把暂停的起点一次次往后推 */
	@Test
	void lateFailuresDoNotRestartThePause() {
		InnerBreakers.Ticket slow = enter(A, PATH);
		trip(A, PATH);

		now.addAndGet(6_000);
		slow.close();

		now.addAndGet(PAUSE - 6_000);
		assertThat(enter(A, PATH)).isNotNull();
	}

	@Test
	void aLateAnswerRestoresNormalService() {
		InnerBreakers.Ticket slow = enter(A, PATH);
		trip(A, PATH);

		slow.answered();
		slow.close();

		assertThat(open(A, PATH)).isFalse();
	}

	/** 试探的请求还在路上时被别的答复恢复了：它随后的失败按普通的一次失败记，试探的名额不会卡住 */
	@Test
	void aProbeThatFailsAfterRecoveryCountsAsOneOrdinaryFailure() {
		InnerBreakers.Ticket slow = enter(A, PATH);
		trip(A, PATH);
		now.addAndGet(PAUSE);
		InnerBreakers.Ticket probe = enter(A, PATH);
		slow.answered();
		slow.close();

		probe.close();

		assertThat(open(A, PATH)).isFalse();
		fail(A, PATH);
		fail(A, PATH);
		assertThat(enter(A, PATH)).as("加上试探那一次，接连失败了三次").isNull();
	}

	/** 试探的请求还在路上时被别的答复恢复了，它自己随后也得到了答复：什么都不用再记 */
	@Test
	void aProbeAnsweredAfterRecoveryChangesNothing() {
		InnerBreakers.Ticket slow = enter(A, PATH);
		trip(A, PATH);
		now.addAndGet(PAUSE);
		InnerBreakers.Ticket probe = enter(A, PATH);
		slow.answered();
		slow.close();

		probe.answered();
		probe.close();

		assertThat(open(A, PATH)).isFalse();
		assertThat(breakers.tracked()).isZero();
	}

	// ---------- 分开记 ----------

	@Test
	void eachInstanceEachPathAndEachTenantIsTrackedOnItsOwn() {
		trip(A, PATH);

		assertThat(open(B, PATH)).as("另一个实例").isFalse();
		assertThat(open(A, OTHER_PATH)).as("同一个实例的另一个接口").isFalse();
		assertThat(breakers.enter(SERVICE, A, PATH, OTHER_TENANT)).as("另一个租户").isNotNull();
		assertThat(enter(A, PATH)).isNull();
	}

	/**
	 * 一个租户自己的故障（比如它的独立数据库连不上）不能连累别的租户：它一直失败、一直占着试探的名额，
	 * 别的租户照常调，调成功了也不会把它的熔断解开
	 */
	@Test
	void oneTenantsTroubleNeverKeepsAnotherTenantOut() {
		trip(A, PATH);
		for (int round = 0; round < 5; round++) {
			now.addAndGet(PAUSE);
			InnerBreakers.Ticket probe = enter(A, PATH);
			assertThat(probe).as("出故障的租户去试探").isNotNull();

			InnerBreakers.Ticket other = breakers.enter(SERVICE, A, PATH, OTHER_TENANT);
			assertThat(other).as("另一个租户照常调").isNotNull();
			other.answered();
			other.close();

			probe.close();
			assertThat(enter(A, PATH)).as("出故障的租户还在熔断中").isNull();
		}
	}

	@Test
	void zeroFailuresTurnsItOff() {
		InnerBreakers off = new InnerBreakers(0, PAUSE, now::get);

		for (int i = 0; i < 50; i++) {
			InnerBreakers.Ticket ticket = off.enter(SERVICE, A, PATH, TENANT);
			assertThat(ticket).isNotNull();
			ticket.close();
		}
		assertThat(off.tracked()).isZero();
	}

	// ---------- 只给正在出故障的留记录 ----------

	/** 平时调得通的不留记录：租户再多、实例换过再多地址，这张表也是空的 */
	@Test
	void callsThatGoThroughLeaveNoRecord() {
		for (int i = 0; i < 10_000; i++) {
			InnerBreakers.Ticket ticket = breakers.enter(SERVICE, "10.2." + i / 250 + "." + i % 250 + ":8102", PATH, "T" + i);
			if (i % 2 == 0) {
				ticket.answered();
			} else {
				ticket.busy();
			}
			ticket.close();
		}

		assertThat(breakers.tracked()).isZero();
	}

	@Test
	void aRecordIsKeptOnlyWhileThereIsTrouble() {
		fail(A, PATH);
		assertThat(breakers.tracked()).isEqualTo(1);

		answer(A, PATH);
		assertThat(breakers.tracked()).isZero();

		trip(A, PATH);
		assertThat(breakers.tracked()).isEqualTo(1);
		now.addAndGet(PAUSE);
		answer(A, PATH);
		assertThat(breakers.tracked()).as("试探成功，恢复以后").isZero();
	}

	/** 出过故障、之后再也没人调的（实例换了地址，那个租户不来了）：表满的时候清掉 */
	@Test
	void recordsNotCalledForALongTimeAreDropped() {
		for (int i = 0; i < InnerBreakers.MAX_TRACKED; i++) {
			breakers.enter(SERVICE, A, PATH, "T" + i).close();
		}
		assertThat(breakers.tracked()).isEqualTo(InnerBreakers.MAX_TRACKED);

		now.addAndGet(InnerBreakers.IDLE_MILLIS + 1);
		List<ILoggingEvent> events = logsDuring(() -> fail(B, PATH));

		assertThat(breakers.tracked()).isEqualTo(1);
		assertThat(events).as("只是清掉很久没用的，不用报").isEmpty();
	}

	/** 「很久没再调过」从最近一次有调用来领凭单、交回凭单算起：还在用的记录不清，哪怕它一直熔断着 */
	@Test
	void recordsStillInUseAreKept() {
		for (int i = 0; i < InnerBreakers.MAX_TRACKED - 2; i++) {
			breakers.enter(SERVICE, B, PATH, "T" + i).close();
		}
		trip(A, PATH);
		fail(A, OTHER_PATH);
		InnerBreakers.Ticket slow = enter(A, OTHER_PATH);
		assertThat(breakers.tracked()).isEqualTo(InnerBreakers.MAX_TRACKED);

		now.addAndGet(InnerBreakers.IDLE_MILLIS - 1_000);
		assertThat(enter(A, PATH)).as("有调用来领凭单：放去试探，一直没回来").isNotNull();
		slow.close();
		now.addAndGet(2_000);
		fail(B, OTHER_PATH);

		assertThat(breakers.tracked()).as("留下刚才还在用的两条，加上新建的一条").isEqualTo(3);
		assertThat(enter(A, PATH)).as("熔断的那条还在，试探的名额还占着").isNull();
		fail(A, OTHER_PATH);
		assertThat(enter(A, OTHER_PATH)).as("另一条记的失败次数也还在：这是第三次").isNull();
	}

	/** 表满了、又没有很久没用的可清：全部清掉重新记，并且留一行日志 */
	@Test
	void whenTheTableIsFullEverythingIsClearedAndSaidSo() {
		for (int i = 0; i < InnerBreakers.MAX_TRACKED; i++) {
			breakers.enter(SERVICE, A, PATH, "T" + i).close();
		}

		List<ILoggingEvent> events = logsDuring(() -> fail(B, PATH));

		assertThat(breakers.tracked()).isEqualTo(1);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
		assertThat(events.get(0).getFormattedMessage()).contains("上限", String.valueOf(InnerBreakers.MAX_TRACKED));
	}

	@Test
	void theTableNeverGrowsPastItsLimit() {
		for (int i = 0; i < InnerBreakers.MAX_TRACKED * 2 + 10; i++) {
			breakers.enter(SERVICE, A, PATH, "T" + i).close();

			assertThat(breakers.tracked()).isLessThanOrEqualTo(InnerBreakers.MAX_TRACKED);
		}
	}

	/** 被清掉的记录要是正好有试探的请求在路上：那个请求回来按普通的请求记，不会报错，也不会留下解不开的熔断 */
	@Test
	void aProbeWhoseRecordWasClearedIsCountedAsAnOrdinaryCall() {
		trip(A, PATH);
		now.addAndGet(PAUSE);
		InnerBreakers.Ticket probe = enter(A, PATH);
		now.addAndGet(InnerBreakers.IDLE_MILLIS + 1);
		for (int i = 0; i < InnerBreakers.MAX_TRACKED; i++) {
			breakers.enter(SERVICE, B, PATH, "T" + i).close();
		}

		probe.close();

		assertThat(open(A, PATH)).isFalse();
		fail(A, PATH);
		fail(A, PATH);
		assertThat(enter(A, PATH)).as("加上试探那一次，接连失败了三次").isNull();
	}

	/**
	 * 很多线程对同一个「实例 + 接口 + 租户」又失败又成功地乱来一阵，记录被不停地建了丢、丢了建。
	 * 之后只要对方答复一次，就必须回到正常：不能留下一条试探名额被占着、再也解不开的熔断
	 */
	@Test
	void afterAnyMixOfConcurrentResultsOneAnswerRestoresNormalService() throws Exception {
		int threads = 32;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> results = new ArrayList<>();
		// 这一阵会熔断、恢复上万次，每次一行日志：跑的时候先关掉
		Logger logger = (Logger) LoggerFactory.getLogger(InnerBreakers.class);
		Level before = logger.getLevel();
		logger.setLevel(Level.OFF);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int t = 0; t < threads; t++) {
				results.add(executor.submit(() -> {
					start.await();
					ThreadLocalRandom random = ThreadLocalRandom.current();
					for (int i = 0; i < 3_000; i++) {
						now.addAndGet(random.nextInt(4_000));
						InnerBreakers.Ticket ticket = enter(A, PATH);
						if (ticket == null) {
							continue;
						}
						switch (random.nextInt(4)) {
							case 0 -> ticket.answered();
							case 1 -> ticket.busy();
							default -> {
							}
						}
						Thread.yield();
						ticket.close();
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> result : results) {
				result.get(30, TimeUnit.SECONDS);
			}
		} finally {
			logger.setLevel(before);
		}

		now.addAndGet(PAUSE);
		InnerBreakers.Ticket last = enter(A, PATH);
		assertThat(last).as("过了暂停的时间，总该放一个请求过去").isNotNull();
		last.answered();
		last.close();

		assertThat(open(A, PATH)).isFalse();
		assertThat(breakers.tracked()).isZero();
	}

	// ---------- 日志 ----------

	/** 熔断、试了还不通、恢复，各留一行，写明是哪个服务的哪个接口、哪个实例、哪个租户；熔断中被挡住的调用不再各打一行 */
	@Test
	void stateChangesAreLoggedOnceEach() {
		List<ILoggingEvent> events = logsDuring(() -> {
			trip(A, PATH);
			for (int i = 0; i < 10; i++) {
				enter(A, PATH);
			}
			now.addAndGet(PAUSE);
			fail(A, PATH);
			now.addAndGet(PAUSE);
			answer(A, PATH);
		});

		assertThat(events).hasSize(3);
		assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
		assertThat(events.get(0).getFormattedMessage()).contains(SERVICE, PATH, A, "租户 " + TENANT, "接连失败 3 次", "10 秒");
		assertThat(events.get(1).getLevel()).isEqualTo(Level.WARN);
		assertThat(events.get(1).getFormattedMessage()).contains(SERVICE, PATH, A, "租户 " + TENANT, "还是不通");
		assertThat(events.get(2).getLevel()).isEqualTo(Level.INFO);
		assertThat(events.get(2).getFormattedMessage()).contains(SERVICE, PATH, A, "租户 " + TENANT, "恢复了", "20 秒");
	}

	/** 停了多久按秒写，不是整秒的留一位小数，不写成一串毫秒 */
	@Test
	void theRecoveryLineSaysHowLongCallsWereHeldBack() {
		trip(A, PATH);
		now.addAndGet(PAUSE + 4_130);

		List<ILoggingEvent> events = logsDuring(() -> answer(A, PATH));

		assertThat(events).hasSize(1);
		assertThat(events.get(0).getFormattedMessage()).contains("恢复了", "前后停了 14.1 秒");
	}

	// ---------- 辅助：没写租户的都是同一个租户 ----------

	private InnerBreakers.Ticket enter(String instance, String path) {
		return breakers.enter(SERVICE, instance, path, TENANT);
	}

	private void fail(String instance, String path) {
		enter(instance, path).close();
	}

	private void answer(String instance, String path) {
		InnerBreakers.Ticket ticket = enter(instance, path);
		ticket.answered();
		ticket.close();
	}

	private void busy(String instance, String path) {
		InnerBreakers.Ticket ticket = enter(instance, path);
		ticket.busy();
		ticket.close();
	}

	/** 接连失败三次，让它熔断 */
	private void trip(String instance, String path) {
		for (int i = 0; i < 3; i++) {
			fail(instance, path);
		}
	}

	/** 是不是熔断中。没熔断时领到的凭单按「忙」交回，不改变失败的次数 */
	private boolean open(String instance, String path) {
		InnerBreakers.Ticket ticket = enter(instance, path);
		if (ticket == null) {
			return true;
		}
		ticket.busy();
		ticket.close();
		return false;
	}

	private static List<ILoggingEvent> logsDuring(Runnable action) {
		Logger logger = (Logger) LoggerFactory.getLogger(InnerBreakers.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
		}
		return List.copyOf(appender.list);
	}
}
