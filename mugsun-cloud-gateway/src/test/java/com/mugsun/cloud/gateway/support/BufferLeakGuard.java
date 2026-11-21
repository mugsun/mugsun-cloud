package com.mugsun.cloud.gateway.support;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 网关的测试里有缓冲区没有释放时，让测试不通过。
 *
 * <p>网关转发的请求体和响应体放在 Netty 的缓冲区里，用完要有人释放，漏了内存就只涨不落。Netty 能发现「没释放就被垃圾回收了」的缓冲区，
 * 但默认只抽查很少一部分，发现了也只往日志里打一条 ERROR，测试照样通过。这里改成每一块都跟踪，
 * 每个测试类跑完以后催一次垃圾回收，有发现就让这个测试类不通过。
 *
 * <p>对本模块所有的测试类生效：登记在 {@code META-INF/services} 里，由 {@code junit-platform.properties} 打开自动登记。
 *
 * <p>发现比漏掉要晚（要等垃圾回收），所以报出来的可能是前面某个测试类漏的。这是尽力而为的检查：
 * 报出来的一定是真漏了，没报不等于一定没漏。同一种漏法（碰过它的代码位置完全一样）整个测试过程只报一次。
 */
public final class BufferLeakGuard implements AfterAllCallback {

	private static final Queue<String> LEAKS = new ConcurrentLinkedQueue<>();

	static {
		ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
		ByteBufUtil.setLeakListener((resourceType, records) -> LEAKS.add(records));
	}

	@Override
	public void afterAll(ExtensionContext context) {
		// Netty 只在自己的 ERROR 日志开着的时候才报告，关掉以后这里什么都收不到：宁可报错，不要悄悄失效
		if (!InternalLoggerFactory.getInstance(ResourceLeakDetector.class).isErrorEnabled()) {
			throw new AssertionError("日志配置把 io.netty.util.ResourceLeakDetector 的 ERROR 关掉了，"
				+ "这时 Netty 不报告没释放的缓冲区，漏了也发现不了。请把它打开");
		}

		// 两轮：第二轮的标记被登记出来时，第一轮回收掉的对象都已经登记完了
		collectGarbage();
		collectGarbage();
		// Netty 在分配缓冲区的时候才去看有没有「没释放就被回收了」的，所以分配一块让它看
		ByteBufAllocator.DEFAULT.buffer(1).release();

		List<String> leaks = new ArrayList<>();
		for (String leak = LEAKS.poll(); leak != null; leak = LEAKS.poll()) {
			leaks.add(leak);
		}
		if (!leaks.isEmpty()) {
			throw new AssertionError("有缓冲区没有释放就被回收了，一共 " + leaks.size() + " 种。"
				+ "是这个测试类或者它前面的测试类里漏的：读了请求体、响应体的代码没有释放它，"
				+ "或者测试里有响应没读完（访问网关请用 GatewayClients）。"
				+ "下面是第一种：先列它最近被碰过的地方，最后的 Created at 是它被创建的地方。\n" + leaks.get(0));
		}
	}

	/** 催一次垃圾回收，等到它把回收掉的对象登记出来 */
	private static void collectGarbage() {
		ReferenceQueue<Object> collected = new ReferenceQueue<>();
		WeakReference<Object> marker = new WeakReference<>(new Object(), collected);
		System.gc();
		try {
			collected.remove(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		Reference.reachabilityFence(marker);
	}
}
