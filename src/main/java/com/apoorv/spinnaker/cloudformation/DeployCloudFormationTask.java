package com.apoorv.spinnaker.cloudformation;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder;
import com.amazonaws.services.cloudformation.model.CreateStackRequest;
import com.amazonaws.services.cloudformation.model.CreateStackResult;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.netflix.spinnaker.orca.api.pipeline.Task;
import com.netflix.spinnaker.orca.api.pipeline.TaskResult;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class DeployCloudFormationTask implements Task {

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 15000;
    private static final int MAX_TEMPLATE_BODY_SIZE = 51200; // 50 KB
    private static final String DEFAULT_S3_BUCKET = "apmishra-cfn-templates";

    @Override
    public TaskResult execute(StageExecution stage) {
        Map<String, Object> context = stage.getContext();
        String stackName = (String) context.getOrDefault(
                "stackName", "spinnaker-cfn-" + UUID.randomUUID().toString().substring(0, 8));

        // Fetch template from GitHub
        Object templateGithubObj = context.get("templateGithubUrl");
        String githubToken = (String) context.get("githubToken");
        String templateBody = null;

        if (templateGithubObj != null && templateGithubObj instanceof String) {
            String templateGithubUrl = (String) templateGithubObj;
            try {
                templateBody = fetchTemplateFromGitHub(templateGithubUrl, githubToken);
            } catch (Exception e) {
                return TaskResult.builder(ExecutionStatus.TERMINAL)
                        .context(Map.of("error", "Failed to fetch template from GitHub: " + e.getMessage()))
                        .build();
            }
        }

        AmazonCloudFormation cf = AmazonCloudFormationClientBuilder.defaultClient();
        CreateStackRequest req = new CreateStackRequest()
                .withStackName(stackName)
                .withCapabilities("CAPABILITY_NAMED_IAM");

        String usedTemplateUrl = null;

        try {
            if (templateBody != null && templateBody.length() <= MAX_TEMPLATE_BODY_SIZE) {
                req.withTemplateBody(templateBody);
            } else if (templateBody != null && templateBody.length() > MAX_TEMPLATE_BODY_SIZE) {
                // Upload to S3 with pre-signed URL
                AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
                String key = stackName + "-" + UUID.randomUUID().toString().substring(0, 8) + ".yaml";
                ByteArrayInputStream inputStream = new ByteArrayInputStream(templateBody.getBytes(StandardCharsets.UTF_8));
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(templateBody.getBytes(StandardCharsets.UTF_8).length);
                metadata.setContentType("text/yaml");
                s3.putObject(DEFAULT_S3_BUCKET, key, inputStream, metadata);

                // Generate pre-signed URL valid for 1 hour
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.HOUR, 1);
                Date expiration = cal.getTime();

                GeneratePresignedUrlRequest presignedRequest = new GeneratePresignedUrlRequest(DEFAULT_S3_BUCKET, key)
                        .withMethod(HttpMethod.GET)
                        .withExpiration(expiration);

                usedTemplateUrl = s3.generatePresignedUrl(presignedRequest).toString();
                req.withTemplateURL(usedTemplateUrl);
            } else {
                // Check if user provided templateS3Url
                String templateS3Url = (String) context.get("templateS3Url");
                if (templateS3Url == null) {
                    return TaskResult.builder(ExecutionStatus.TERMINAL)
                            .context(Map.of("error", "No template provided"))
                            .build();
                }
                req.withTemplateURL(templateS3Url);
                usedTemplateUrl = templateS3Url;
            }

            CreateStackResult result = cf.createStack(req);
            Map<String, Object> out = new HashMap<>();
            out.put("stackName", stackName);
            out.put("stackId", result.getStackId());
            out.put("cloudFormationResponse", result);
            if (usedTemplateUrl != null) out.put("templateS3Url", usedTemplateUrl);

            return TaskResult.builder(ExecutionStatus.SUCCEEDED)
                    .context(out)
                    .build();

        } catch (Exception e) {
            return TaskResult.builder(ExecutionStatus.TERMINAL)
                    .context(Map.of("error", "AWS CloudFormation failed: " + e.getMessage()))
                    .build();
        }
    }

    private String fetchTemplateFromGitHub(String templateGithubUrl, String token) throws Exception {
        StringBuilder template = new StringBuilder();
        URL url = new URL(templateGithubUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);

        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github.v3.raw");
        }

        conn.setRequestMethod("GET");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("GitHub API responded with code: " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                template.append(line).append("\n");
            }
        }
        return template.toString();
    }
}
