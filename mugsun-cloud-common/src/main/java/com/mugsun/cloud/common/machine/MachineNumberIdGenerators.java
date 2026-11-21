package com.mugsun.cloud.common.machine;

import cn.hutool.core.lang.Singleton;
import cn.hutool.core.lang.Snowflake;
import com.mybatisflex.core.FlexGlobalConfig;
import com.mybatisflex.core.keygen.IKeyGenerator;
import com.mybatisflex.core.keygen.KeyGeneratorFactory;
import com.mybatisflex.core.keygen.KeyGenerators;
import com.mybatisflex.core.keygen.impl.FlexIDKeyGenerator;
import com.mybatisflex.spring.boot.MyBatisFlexCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 把机器号交给生成主键的三处：
 * <ul>
 *   <li>MyBatis-Flex 的 flexId：绝大多数表的主键。机器号直接用</li>
 *   <li>Hutool 的雪花算法（{@code IdUtil.getSnowflakeNextId()}）：站内信、流水号、埋点等地方自己取号时用</li>
 *   <li>Warm-Flow 流程引擎：它给自己的表生成主键，默认每个进程都用同一组号</li>
 * </ul>
 * 后两处把机器号分成两段（workerId、datacenterId），每段 0 到 31，这里按「除以 32 的余数、商加一」拆开。
 *
 * <p>它是一个 {@link MyBatisFlexCustomizer}：MyBatis-Flex 在装配的早期就会用到它，早于任何 Mapper。构造时要
 * {@link MachineNumber}，所以 Spring 会先把号领好，启动过程中写库用的也是领到的号。
 *
 * <p>Mapper 登记时会把当时的主键生成器记下来，以后一直用那一个，Warm-Flow 也是第一次用到时记下来。所以登记进去的都是
 * 一个「转手」的生成器：机器号换了，只换它里面真正干活的那个。
 *
 * <p>没领到号时（{@link MachineNumber#UNASSIGNED}，也就是 1）：flexId 用 1，Warm-Flow 用（1，1），正好是这两个库自己的默认值，
 * 和没有这个类时一样；领到的号从 2 开始，拆出来也不会是（1，1），所以领到号的实例和没领号的（领号失败的、
 * 关掉这个功能的、还没升级的）不会生成出相同的主键。Hutool 退回它自己的默认值（按网卡地址和进程号算出来的）。
 */
@Component
@ConditionalOnProperty(name = "mugsun.cloud.machine-number.enabled", havingValue = "true", matchIfMissing = true)
public class MachineNumberIdGenerators implements MyBatisFlexCustomizer {

	private static final Logger log = LoggerFactory.getLogger(MachineNumberIdGenerators.class);

	/** Hutool 和 Warm-Flow 的雪花算法里，机器号的每一段是 0 到 31 */
	static final int SEGMENT = 32;

	static long workerIdOf(int number) {
		return number % SEGMENT;
	}

	/** 加一：这样 1 号拆出来是（1，1），和 Warm-Flow 自己的默认值相同。机器号最大 99，这一段最大是 4 */
	static long datacenterIdOf(int number) {
		return number / SEGMENT + 1;
	}

	private final SwitchableFlexId flexId = new SwitchableFlexId();

	private final WarmFlowIds warmFlow = WarmFlowIds.ifPresent();

	private int current = -1;

	private Snowflake hutool;

	@Autowired
	public MachineNumberIdGenerators(MachineNumber machineNumber) {
		machineNumber.onChange(this::use);
	}

	/** 单测用：不接 {@link MachineNumber}，直接调 {@link #use} */
	MachineNumberIdGenerators() {
	}

	@Override
	public void customize(FlexGlobalConfig globalConfig) {
		KeyGeneratorFactory.register(KeyGenerators.flexId, flexId);
	}

	synchronized void use(int number) {
		// 号没变就不换：换一个新的生成器，它不知道旧的那个在这一毫秒里已经发到第几个序号了
		if (number == current) {
			return;
		}
		current = number;
		flexId.use(number);
		useForHutool(number);
		if (warmFlow != null) {
			warmFlow.use(number);
		}
	}

	private void useForHutool(int number) {
		if (number != MachineNumber.UNASSIGNED) {
			hutool = new Snowflake(workerIdOf(number), datacenterIdOf(number));
			// IdUtil.getSnowflake() 取的就是这个单例
			Singleton.put(hutool);
		} else if (hutool != null) {
			// 只在换过的情况下才拿掉：拿掉以后 Hutool 下次用到时按它自己的默认值再建一个
			Singleton.remove(Snowflake.class);
			hutool = null;
		}
	}

	/** 登记到 MyBatis-Flex 里的是它 */
	static final class SwitchableFlexId implements IKeyGenerator {

		private volatile IKeyGenerator target = new FlexIDKeyGenerator(MachineNumber.UNASSIGNED);

		void use(int number) {
			target = new FlexIDKeyGenerator(number);
		}

		@Override
		public Object generate(Object entity, String keyColumn) {
			return target.generate(entity, keyColumn);
		}
	}

	/**
	 * Warm-Flow。它不在公共层的依赖里（只有 system 和 ai 带着），所以用反射；这里写死的类名和方法名，
	 * 在 system 服务的单测（WarmFlowMachineNumberTest）里对着真的库验过。
	 *
	 * <p>Warm-Flow 第一次生成主键时，如果有人用 {@code IdUtils.setInstanceNative} 交给过它一个生成器就用那个，
	 * 否则自己建一个 {@code SnowFlakeId19(1, 1)}。这里交给它的生成器，里面用的还是它自己的 SnowFlakeId19，只是换了机器号：
	 * 主键的格式和原来一样，比库里已有的都大。
	 */
	static final class WarmFlowIds {

		private static final String ID_UTILS = "org.dromara.warm.flow.core.utils.IdUtils";

		private static final String KEN_GEN = "org.dromara.warm.flow.core.keygen.KenGen";

		private static final String SNOW_FLAKE_ID_19 = "org.dromara.warm.flow.core.keygen.SnowFlakeId19";

		private final Class<?> kenGen;

		private final Method nextId;

		private final Method setInstanceNative;

		private final Constructor<?> newSnowFlakeId19;

		private volatile Object target;

		private boolean installed;

		private WarmFlowIds(ClassLoader loader) throws ReflectiveOperationException {
			this.kenGen = Class.forName(KEN_GEN, false, loader);
			this.nextId = kenGen.getMethod("nextId");
			this.setInstanceNative = Class.forName(ID_UTILS, false, loader).getMethod("setInstanceNative", kenGen);
			this.newSnowFlakeId19 = Class.forName(SNOW_FLAKE_ID_19, false, loader).getConstructor(long.class, long.class);
		}

		/** 这个服务没带 Warm-Flow 时返回 null */
		static WarmFlowIds ifPresent() {
			ClassLoader loader = ClassUtils.getDefaultClassLoader();
			if (!ClassUtils.isPresent(ID_UTILS, loader)) {
				return null;
			}
			try {
				return new WarmFlowIds(loader);
			} catch (ReflectiveOperationException | LinkageError e) {
				log.warn("Warm-Flow 的版本和这里预期的不一样，流程引擎的主键没有用上机器号：{}", e.toString());
				return null;
			}
		}

		void use(int number) {
			try {
				// 前一个参数是 workerId，后一个是 datacenterId
				target = newSnowFlakeId19.newInstance(workerIdOf(number), datacenterIdOf(number));
				if (!installed) {
					Object switchable = Proxy.newProxyInstance(kenGen.getClassLoader(), new Class<?>[] {kenGen},
						(proxy, method, args) -> switch (method.getName()) {
							case "nextId" -> nextIdOf(target);
							case "hashCode" -> System.identityHashCode(proxy);
							case "equals" -> proxy == args[0];
							default -> "按机器号生成 Warm-Flow 的主键";
						});
					setInstanceNative.invoke(null, switchable);
					installed = true;
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				log.warn("把机器号 {} 交给 Warm-Flow 时出错，流程引擎的主键没有用上它：{}", number, e.toString());
			}
		}

		private Object nextIdOf(Object generator) throws Throwable {
			try {
				return nextId.invoke(generator);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}
	}
}
