package org.eclipse.edc.dse.telemetry.services.report;

import org.eclipse.edc.dse.telemetry.services.storage.ReportStorageService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportGeneratorSchedulerTest {

    private static Monitor monitor;
    private static ReportStorageService reportStorageService;

    @BeforeAll
    void setup() {
        monitor = mock(Monitor.class);
        reportStorageService = mock(ReportStorageService.class);
    }

    @Test
    @DisplayName("Should calculate correct delay when before 2nd of month")
    void shouldCalculateCorrectDelay_WhenBefore2ndOfMonth() {
        // simulate now: 1st Oct, 10:00 AM
        Clock fixedClock = Clock.fixed(
                ZonedDateTime.of(2025, 10, 1, 10, 0, 0, 0, ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()
        );

        ReportGeneratorScheduler reportGeneratorScheduler = new ReportGeneratorScheduler(monitor, reportStorageService, fixedClock);

        long delaySeconds = reportGeneratorScheduler.computeDelayUntilNextMonthlyRun();
        // 16 hours to next run
        assertEquals(16 * 3600, delaySeconds);
    }

    @Test
    @DisplayName("Should calculate correct delay when after 2nd of month")
    void shouldCalculateCorrectDelay_WhenAfter2ndOfMonth() {
        // simulate now: 2nd Oct, 03:00 AM
        Clock fixedClock = Clock.fixed(
                ZonedDateTime.of(2025, 10, 2, 3, 0, 0, 0, ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()
        );

        ReportGeneratorScheduler reportGeneratorScheduler = new ReportGeneratorScheduler(monitor, reportStorageService, fixedClock);

        long delaySeconds = reportGeneratorScheduler.computeDelayUntilNextMonthlyRun();
        // next month 2nd Nov 02:00 => ~30 days
        ZonedDateTime nextRun = ZonedDateTime.of(2025, 11, 2, 2, 0, 0, 0, ZoneId.systemDefault());
        long expected = Duration.between(ZonedDateTime.now(fixedClock), nextRun).getSeconds();

        assertEquals(expected, delaySeconds);
    }
}
