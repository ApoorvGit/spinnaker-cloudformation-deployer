package com.apoorv.spinnaker.cloudformation;

import com.netflix.spinnaker.orca.api.pipeline.graph.StageDefinitionBuilder;
import com.netflix.spinnaker.orca.api.pipeline.graph.TaskNode;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import org.springframework.stereotype.Component;

@Component
public class DeployCloudFormationStage implements StageDefinitionBuilder {
    @Override
    public void taskGraph(StageExecution stage, TaskNode.Builder builder) {
        builder.withTask("deployCloudFormation", DeployCloudFormationTask.class);
    }
}
