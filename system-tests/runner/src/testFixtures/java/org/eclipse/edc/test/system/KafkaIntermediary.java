package org.eclipse.edc.test.system;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class KafkaIntermediary {

    private static final String PROXY_BROKER = "proxy-provider:30001";
    private static final String SASL_USERNAME = "provider";
    private static final String SASL_PASSWORD = "secret1";
    private static final String CA_CERT_PATH = "/etc/kafka/ca.crt";

    public static void provider_publish(String kafkacatPod) {
        String message = "Hello from provider!";
        ProcessBuilder pb = getProcessBuilder(kafkacatPod);

        try {
            Process process = pb.start();

            StringBuilder outputCapture = new StringBuilder();
            Thread drainThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputCapture.append(line).append("\n");
                    }
                } catch (Exception ignored) {
                }
            });
            drainThread.setDaemon(true);
            drainThread.start();

            try (OutputStream os = process.getOutputStream();
                 PrintWriter writer = new PrintWriter(os, true)) {
                writer.println(message);
            }

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException(
                        "kcat producer timed out. Output: " + outputCapture);
            }

            drainThread.join(2000);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "kcat producer exited with code " + exitCode + ". Output: " + outputCapture);
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish message to Kafka via kcat", e);
        }
    }

    public static boolean waitForKafkaMessage(String serviceName, int podPort, String topic,
                                              String expectedMessage, Duration timeout,
                                              String kafkacatPod) throws Exception {
        List<String> fullCmd = new ArrayList<>(Arrays.asList(
                "kubectl", "exec", "-i", kafkacatPod, "-n", "default", "--",
                "kcat",
                "-b", serviceName + ":" + podPort,
                "-t", topic,
                "-C", "-o", "beginning", "-q",
                "-X", "security.protocol=SASL_SSL",
                "-X", "sasl.mechanism=PLAIN",
                "-X", "sasl.username=" + SASL_USERNAME,
                "-X", "sasl.password=" + SASL_PASSWORD,
                "-X", "ssl.ca.location=" + CA_CERT_PATH
        ));

        ProcessBuilder pb = new ProcessBuilder(fullCmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        AtomicBoolean found = new AtomicBoolean(false);

        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(expectedMessage)) {
                        found.set(true);
                        process.destroyForcibly();
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        });

        readerThread.setDaemon(true);
        readerThread.start();
        readerThread.join(timeout.toMillis());

        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }

        return found.get();
    }

    private static ProcessBuilder getProcessBuilder(String kafkacatPod) {
        List<String> cmd = Arrays.asList(
                "kubectl", "exec", "-i", kafkacatPod, "-n", "default",
                "--", "kcat",
                "-b", PROXY_BROKER,
                "-t", "tst-topic",
                "-P",
                "-X", "security.protocol=SASL_SSL",
                "-X", "sasl.mechanism=PLAIN",
                "-X", "sasl.username=" + SASL_USERNAME,
                "-X", "sasl.password=" + SASL_PASSWORD,
                "-X", "ssl.ca.location=" + CA_CERT_PATH
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        return pb;
    }
}