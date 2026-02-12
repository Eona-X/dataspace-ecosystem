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
import java.util.stream.Collectors;

public class KafkaIntermediary {

    public static void provider_publish(String kafkacatPod) {
        String message = "Hello from provider!";
        int podPort = 9092;

        List<String> cmd = Arrays.asList(
                "kubectl", "exec", "-i", kafkacatPod, "-n", "default",
                "--", "kcat",
                "-b", "broker.default.svc.cluster.local:" + podPort,
                "-t", "tst-topic",
                "-P",
                "-X", "security.protocol=PLAINTEXT"
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();

            try (OutputStream os = process.getOutputStream();
                    PrintWriter writer = new PrintWriter(os, true)) {
                writer.println(message);
            }

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);

            if (!finished) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                throw new RuntimeException("kcat producer did not finish properly (timed out).");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String output = reader.lines().collect(Collectors.joining("\n"));
                    throw new RuntimeException("kcat producer failed: " + output);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to publish message to Kafka via kcat", e);
        }
    }


    public static boolean waitForKafkaMessage(String serviceName, int podPort, String topic,
                                              String expectedMessage, Duration timeout, String kafkacatPod) throws Exception {
        List<String> kcatCmd = Arrays.asList(
                "kcat",
                "-b", serviceName + ":" + podPort,
                "-t", topic,
                "-C",
                "-o", "beginning",
                "-X", "security.protocol=SASL_PLAINTEXT",
                "-X", "sasl.mechanism=PLAIN",
                "-X", "sasl.username=admin",
                "-X", "sasl.password=admin-secret"
        );

        List<String> fullCmd = new ArrayList<>();
        fullCmd.addAll(Arrays.asList("kubectl", "exec", "-i", kafkacatPod, "--", "sh", "-c",
                String.join(" ", kcatCmd)));

        ProcessBuilder pb = new ProcessBuilder(fullCmd);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        long start = System.currentTimeMillis();
        long maxWaitMillis = timeout.toMillis();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

                if (line.contains(expectedMessage)) {
                    process.destroyForcibly();
                    return true;
                }

                if (System.currentTimeMillis() - start > maxWaitMillis) {
                    break;
                }
            }
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        }

        return output.toString().contains(expectedMessage);
    }

}