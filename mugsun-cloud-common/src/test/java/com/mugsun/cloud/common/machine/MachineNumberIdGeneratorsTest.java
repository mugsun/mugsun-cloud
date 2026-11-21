package com.mugsun.cloud.common.machine;

import cn.hutool.core.lang.Singleton;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.mybatisflex.core.FlexGlobalConfig;
import com.mybatisflex.core.keygen.IKeyGenerator;
import com.mybatisflex.core.keygen.KeyGeneratorFactory;
import com.mybatisflex.core.keygen.KeyGenerators;
import com.mybatisflex.core.keygen.impl.FlexIDKeyGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机器号有没有真的进到生成出来的主键里：MyBatis-Flex 的 flexId 和 Hutool 的雪花算法，用的都是真的库。
 * Warm-Flow 不在公共层的依赖里，那一处在 system 服务的 WarmFlowMachineNumberTest 里测。
 */
class MachineNumberIdGeneratorsTest {

	private final MachineNumberIdGenerators generators = new MachineNumberIdGenerators();

	/** 两个库都把生成器放在静态变量里，测完还原，不影响同一个进程里的其它测试 */
	@AfterEach
	void restoreLibraryDefaults() {
		KeyGeneratorFactory.register(KeyGenerators.flexId, new FlexIDKeyGenerator());
		Singleton.remove(Snowflake.class);
	}

	/** MyBatis-Flex 登记 Mapper 时拿走、以后一直用的就是这个对象 */
	private IKeyGenerator registeredFlexId() {
		generators.customize(FlexGlobalConfig.getDefaultConfig());
		return KeyGeneratorFactory.getKeyGenerator(KeyGenerators.flexId);
	}

	/** flexId 从右往左：随机数两位、机器号两位、毫秒内的序号两位，再往左是时间 */
	private static long machineNumberIn(Object flexId) {
		return (Long) flexId / 100 % 100;
	}

	@Test
	void flexIdCarriesTheMachineNumber() {
		IKeyGenerator registered = registeredFlexId();

		generators.use(37);
		assertThat(machineNumberIn(registered.generate(null, "id"))).isEqualTo(37);

		// 机器号换了：还是经同一个对象生成，带的是新号
		generators.use(99);
		assertThat(machineNumberIn(registered.generate(null, "id"))).isEqualTo(99);
	}

	@Test
	void beforeANumberIsKnownFlexIdUsesTheFallback() {
		assertThat(machineNumberIn(registeredFlexId().generate(null, "id"))).isEqualTo(MachineNumber.UNASSIGNED);
	}

	/** 两个实例在同一毫秒里各生成一批：机器号不同就不会有相同的；机器号相同（改之前的情况）会有 */
	@Test
	void twoInstancesProduceTheSameFlexIdOnlyWhenTheyShareANumber() {
		assertThat(sameIdsBetween(7, 8)).isZero();
		assertThat(sameIdsBetween(7, 7)).isPositive();
	}

	private static int sameIdsBetween(int oneNumber, int otherNumber) {
		MachineNumberIdGenerators.SwitchableFlexId one = new MachineNumberIdGenerators.SwitchableFlexId();
		MachineNumberIdGenerators.SwitchableFlexId other = new MachineNumberIdGenerators.SwitchableFlexId();
		one.use(oneNumber);
		other.use(otherNumber);
		Set<Object> seen = new HashSet<>();
		int same = 0;
		for (int i = 0; i < 20_000; i++) {
			if (!seen.add(one.generate(null, "id"))) {
				same++;
			}
			if (!seen.add(other.generate(null, "id"))) {
				same++;
			}
		}
		return same;
	}

	@Test
	void hutoolSnowflakeCarriesTheMachineNumber() {
		// 70 = 2 × 32 + 6：workerId 是余数 6，datacenterId 是商加一
		generators.use(70);

		long id = IdUtil.getSnowflakeNextId();
		assertThat(IdUtil.getSnowflake().getWorkerId(id)).isEqualTo(6);
		assertThat(IdUtil.getSnowflake().getDataCenterId(id)).isEqualTo(3);
	}

	@Test
	void withoutANumberHutoolGoesBackToItsOwnDefault() {
		generators.use(70);
		Snowflake ours = IdUtil.getSnowflake();

		generators.use(MachineNumber.UNASSIGNED);
		assertThat(IdUtil.getSnowflake()).isNotSameAs(ours);
	}

	@Test
	void withoutANumberFromTheStartHutoolIsLeftAlone() {
		Snowflake libraryDefault = IdUtil.getSnowflake();

		generators.use(MachineNumber.UNASSIGNED);
		assertThat(IdUtil.getSnowflake()).isSameAs(libraryDefault);
	}

	/** gis 和 track 没带 Warm-Flow：没有它的类也不能出错。公共层自己的测试里正好没有这个库 */
	@Test
	void worksInAServiceWithoutWarmFlow() {
		assertThat(MachineNumberIdGenerators.WarmFlowIds.ifPresent()).isNull();

		generators.use(5);
		assertThat(machineNumberIn(registeredFlexId().generate(null, "id"))).isEqualTo(5);
	}

	/** 接上 MachineNumber：领到几号就用几号，号被别的实例领走、换了以后跟着换 */
	@Test
	void followsTheMachineNumber() {
		MachineNumberTest.MemoryStore store = new MachineNumberTest.MemoryStore();
		MachineNumber machineNumber = new MachineNumber(store, Duration.ofMinutes(3), "a");
		machineNumber.afterPropertiesSet();
		try {
			MachineNumberIdGenerators following = new MachineNumberIdGenerators(machineNumber);
			following.customize(FlexGlobalConfig.getDefaultConfig());
			IKeyGenerator registered = KeyGeneratorFactory.getKeyGenerator(KeyGenerators.flexId);
			assertThat(machineNumberIn(registered.generate(null, "id"))).isEqualTo(2);

			store.owners.put(2, "别的实例");
			machineNumber.renew();
			assertThat(machineNumberIn(registered.generate(null, "id"))).isEqualTo(3);
		} finally {
			machineNumber.stop();
		}
	}
}
