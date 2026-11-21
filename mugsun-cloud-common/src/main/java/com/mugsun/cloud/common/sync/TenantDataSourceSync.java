package com.mugsun.cloud.common.sync;

import com.mugsun.boot.datasource.entity.SysTenantDatasource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 让本进程里的租户独立数据源和库里的配置保持一致。
 *
 * <p>单体版里，管理员保存或删除租户数据源时，控制器直接改本进程的连接池。拆成多个服务后，这个操作只发生在
 * system 服务里，其它服务不知道：租户已经改用独立库了，它们还在往主库写。这里每轮读一遍启用中的配置，
 * 和上一轮比较：新增或改过的重新注册，不再启用的注销，注册失败的下一轮接着试。
 *
 * <p>这里只做比较，注册、探活、注销仍由 mugsun-boot 的 {@code TenantDataSourceRegistry} 完成。
 */
public class TenantDataSourceSync {

	private static final Logger log = LoggerFactory.getLogger(TenantDataSourceSync.class);

	private final Supplier<List<SysTenantDatasource>> enabledConfigs;

	private final Predicate<String> registered;

	private final Consumer<SysTenantDatasource> register;

	private final Consumer<String> unregister;

	/** 租户编号 → 已经生效的那份配置的指纹 */
	private final Map<String, String> applied = new HashMap<>();

	/** 租户编号 → 上次注册失败的那份配置的指纹。同一份配置反复失败时只记一次日志 */
	private final Map<String, String> failed = new HashMap<>();

	/**
	 * @param enabledConfigs 读出库里启用中的全部配置
	 * @param registered     某个租户的独立数据源在本进程里是否已经生效
	 * @param register       注册或重建某个租户的数据源，连不上时抛异常
	 * @param unregister     注销某个租户的数据源，该租户改回主库
	 */
	public TenantDataSourceSync(Supplier<List<SysTenantDatasource>> enabledConfigs, Predicate<String> registered,
								Consumer<SysTenantDatasource> register, Consumer<String> unregister) {
		this.enabledConfigs = enabledConfigs;
		this.registered = registered;
		this.register = register;
		this.unregister = unregister;
	}

	/** 比较一轮。返回这一轮改动了几个租户的数据源（注册成功的加上注销的）。 */
	public synchronized int run() {
		Map<String, SysTenantDatasource> wanted = new LinkedHashMap<>();
		for (SysTenantDatasource config : enabledConfigs.get()) {
			if (config != null && config.getTenantCode() != null) {
				wanted.put(config.getTenantCode(), config);
			}
		}
		int changed = 0;
		for (Map.Entry<String, SysTenantDatasource> entry : wanted.entrySet()) {
			String tenant = entry.getKey();
			String print = fingerprint(entry.getValue());
			boolean live = registered.test(tenant);
			if (live && !applied.containsKey(tenant)) {
				// 启动时已经注册过（或者本进程的控制器刚注册过）：记下这份配置，不重复注册
				applied.put(tenant, print);
				failed.remove(tenant);
				continue;
			}
			if (live && print.equals(applied.get(tenant))) {
				continue;
			}
			try {
				register.accept(entry.getValue());
				if (!registered.test(tenant)) {
					// 注册没报错但也没生效（比如主数据源不支持动态数据源）：按失败记，避免每轮都当成新变化
					throw new IllegalStateException("注册后没有生效");
				}
				applied.put(tenant, print);
				failed.remove(tenant);
				changed++;
				log.info("租户 {} 的独立数据源配置有变化，已按新配置注册", tenant);
			} catch (RuntimeException e) {
				if (!print.equals(failed.put(tenant, print))) {
					log.error("租户 {} 的独立数据源注册失败，该租户的请求{}，下一轮会再试：{}", tenant,
						live ? "仍在用改动前的连接" : "会落到主库", e.getMessage());
				}
			}
		}
		for (String tenant : List.copyOf(applied.keySet())) {
			if (!wanted.containsKey(tenant)) {
				unregister.accept(tenant);
				applied.remove(tenant);
				changed++;
				log.info("租户 {} 的独立数据源已停用或删除，该租户改回主库", tenant);
			}
		}
		failed.keySet().retainAll(wanted.keySet());
		return changed;
	}

	/**
	 * 一份配置的指纹：所有字段（含父类的）的名字和值一起做摘要。不按字段名挑着比，mugsun-boot 给实体加了字段这里不用改。
	 * 密码只参与摘要，不保存、不进日志。
	 */
	static String fingerprint(Object config) {
		StringBuilder text = new StringBuilder();
		for (Class<?> type = config.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			Field[] fields = type.getDeclaredFields();
			Arrays.sort(fields, Comparator.comparing(Field::getName));
			for (Field field : fields) {
				if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
					continue;
				}
				try {
					field.setAccessible(true);
					text.append(type.getName()).append('#').append(field.getName()).append('=')
						.append(field.get(config)).append('\n');
				} catch (ReflectiveOperationException | RuntimeException e) {
					throw new IllegalStateException("读不到 " + type.getName() + " 的字段 " + field.getName(), e);
				}
			}
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
