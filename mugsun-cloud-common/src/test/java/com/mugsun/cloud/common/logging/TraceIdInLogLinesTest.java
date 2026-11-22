package com.mugsun.cloud.common.logging;

import com.mugsun.boot.common.constant.MonitorConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务的每一行日志在级别后面带着请求编号，控制台和日志文件都是：拿着网关给的编号，要能在服务的日志里找到这一次请求。
 * 日志是照服务启动的样子初始化的，见 {@link ServiceLoggingTestBase}。
 */
class TraceIdInLogLinesTest extends ServiceLoggingTestBase {

	private static final String TRACE_ID = "3f6c1d0a-trace-in-log-lines";

	@AfterEach
	void forgetTheTraceId() {
		MDC.remove(MonitorConstants.TRACE_MDC_KEY);
	}

	@Test
	void consoleAndFileLinesCarryTheTraceId(CapturedOutput output) throws Exception {
		initializeLoggingTheWayAServiceDoes();
		Logger logger = LoggerFactory.getLogger(TraceIdInLogLinesTest.class);

		MDC.put(MonitorConstants.TRACE_MDC_KEY, TRACE_ID);
		logger.info("logged-inside-a-request");
		MDC.remove(MonitorConstants.TRACE_MDC_KEY);
		logger.info("logged-outside-a-request");

		String console = output.getOut();
		// 日志文件按服务名分开
		String file = logFile(SERVICE + ".log");

		assertThat(lineWith(console, "logged-inside-a-request")).as("控制台，在请求里打的日志")
			.contains(" INFO [" + TRACE_ID + "]");
		assertThat(lineWith(file, "logged-inside-a-request")).as("日志文件，在请求里打的日志")
			.contains(" INFO [" + TRACE_ID + "]");
		// 不在请求里打的日志（启动、定时任务），这一格是空的
		assertThat(lineWith(console, "logged-outside-a-request")).as("控制台，不在请求里打的日志")
			.contains(" INFO []");
		assertThat(lineWith(file, "logged-outside-a-request")).as("日志文件，不在请求里打的日志")
			.contains(" INFO []");
	}

	private static String lineWith(String lines, String marker) {
		return lines.lines().filter(line -> line.contains(marker)).findFirst()
			.orElseThrow(() -> new AssertionError("没有找到带 " + marker + " 的那一行日志"));
	}
}
