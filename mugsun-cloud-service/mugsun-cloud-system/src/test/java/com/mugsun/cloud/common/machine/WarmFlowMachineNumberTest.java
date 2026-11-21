package com.mugsun.cloud.common.machine;

import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.config.WarmFlow;
import org.dromara.warm.flow.core.utils.IdUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warm-Flow 流程引擎的主键有没有用上机器号。
 *
 * <p>放在 system 服务里测：公共层没有 Warm-Flow 这个依赖，那边是用反射接的（MachineNumberIdGenerators.WarmFlowIds），
 * 这里对着真的库验一遍类名、方法名和主键里各段的位置。Warm-Flow 升级后这些对不上了，这个测试会失败。
 * 包名和公共层的相同，为的是用得到包内可见的方法。
 */
class WarmFlowMachineNumberTest {

	private Object generatorBefore;

	private Object nativeGeneratorBefore;

	private WarmFlow configBefore;

	/**
	 * Warm-Flow 第一次生成主键时把用的生成器记在静态变量里，以后一直用它。每个测试开始前清掉，回到进程刚启动的样子；
	 * 原来的值记下来，测完放回去，不影响同一个进程里的其它测试。
	 */
	@BeforeEach
	void startLikeAFreshProcess() {
		generatorBefore = ReflectionTestUtils.getField(IdUtils.class, "instance");
		nativeGeneratorBefore = ReflectionTestUtils.getField(IdUtils.class, "instanceNative");
		configBefore = FlowEngine.getFlowConfig();
		forgetTheGenerator();
		// 取号时要读配置里的主键类型；这里和项目里一样不配，用默认的
		FlowEngine.setFlowConfig(new WarmFlow());
	}

	@AfterEach
	void putBack() {
		ReflectionTestUtils.setField(IdUtils.class, "instance", generatorBefore);
		ReflectionTestUtils.setField(IdUtils.class, "instanceNative", nativeGeneratorBefore);
		FlowEngine.setFlowConfig(configBefore);
	}

	private static void forgetTheGenerator() {
		ReflectionTestUtils.setField(IdUtils.class, "instance", null);
		ReflectionTestUtils.setField(IdUtils.class, "instanceNative", null);
	}

	/** Warm-Flow 的 SnowFlakeId19：最低 12 位是序号，往上 5 位是 workerId，再往上 5 位是 datacenterId，剩下的是时间 */
	private static long workerIdIn(long id) {
		return (id >> 12) & 31;
	}

	private static long datacenterIdIn(long id) {
		return (id >> 17) & 31;
	}

	/** 改之前的情况：每个进程都用同一组号（1，1） */
	@Test
	void onItsOwnWarmFlowUsesTheSameNumbersInEveryProcess() {
		long id = IdUtils.nextId();

		assertThat(workerIdIn(id)).isEqualTo(1);
		assertThat(datacenterIdIn(id)).isEqualTo(1);
	}

	@Test
	void warmFlowIdsCarryTheMachineNumber() {
		MachineNumberIdGenerators generators = new MachineNumberIdGenerators();

		// 70 = 2 × 32 + 6：workerId 是余数 6，datacenterId 是商加一
		generators.use(70);
		long id = IdUtils.nextId();
		assertThat(workerIdIn(id)).isEqualTo(6);
		assertThat(datacenterIdIn(id)).isEqualTo(3);

		// 这时 Warm-Flow 已经把生成器记下了。机器号换了以后，新主键带的是新号：37 = 1 × 32 + 5
		generators.use(37);
		long next = IdUtils.nextId();
		assertThat(workerIdIn(next)).isEqualTo(5);
		assertThat(datacenterIdIn(next)).isEqualTo(2);
	}

	/** 没领到号时用的是 1，拆开是（1，1），和 Warm-Flow 自己的默认值相同；领到的号（2 到 99）拆开都不是它 */
	@Test
	void withoutANumberItUsesWarmFlowsOwnDefault() {
		new MachineNumberIdGenerators().use(MachineNumber.UNASSIGNED);
		long id = IdUtils.nextId();

		assertThat(workerIdIn(id)).isEqualTo(1);
		assertThat(datacenterIdIn(id)).isEqualTo(1);

		for (int number = MachineNumber.FIRST; number <= MachineNumber.LAST; number++) {
			long workerId = MachineNumberIdGenerators.workerIdOf(number);
			long datacenterId = MachineNumberIdGenerators.datacenterIdOf(number);
			assertThat(workerId == 1 && datacenterId == 1).as("机器号 %s", number).isFalse();
		}
	}

	/** 换了机器号以后，主键还是原来的格式，比以前生成的大：库里已有的数据不受影响 */
	@Test
	void idsKeepTheirFormatAndKeepGrowing() {
		long before = IdUtils.nextId();
		forgetTheGenerator();

		new MachineNumberIdGenerators().use(70);
		long after = IdUtils.nextId();

		assertThat(Long.toString(after)).hasSameSizeAs(Long.toString(before));
		assertThat(after).isGreaterThan(before);
	}
}
