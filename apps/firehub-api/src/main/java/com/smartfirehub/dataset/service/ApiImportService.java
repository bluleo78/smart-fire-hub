package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.ApiImportRequest;
import com.smartfirehub.dataset.dto.ApiImportResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.PipelineDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineExecutionResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.pipeline.service.TriggerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class ApiImportService {

    private final DatasetRepository datasetRepository;
    private final PipelineService pipelineService;
    private final TriggerService triggerService;

    public ApiImportService(DatasetRepository datasetRepository,
                             PipelineService pipelineService,
                             TriggerService triggerService) {
        this.datasetRepository = datasetRepository;
        this.pipelineService = pipelineService;
        this.triggerService = triggerService;
    }

    @Transactional
    public ApiImportResponse createApiImport(Long datasetId, ApiImportRequest request, Long userId) {
        // 1. Verify dataset exists
        var dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        // 2. Build pipeline name
        String pipelineName = (request.pipelineName() != null && !request.pipelineName().isBlank())
                ? request.pipelineName()
                : dataset.name() + " API Import";

        // 3. Build the single API_CALL step
        String loadStrategy = (request.loadStrategy() != null && !request.loadStrategy().isBlank())
                ? request.loadStrategy()
                : "REPLACE";

        PipelineStepRequest stepRequest = new PipelineStepRequest(
                "API 데이터 수집",
                request.pipelineDescription(),
                "API_CALL",
                null,
                datasetId,
                List.of(),
                List.of(),
                loadStrategy,
                request.apiConfig(),
                request.apiConnectionId()
        );

        // 4. Create pipeline via PipelineService
        CreatePipelineRequest createReq = new CreatePipelineRequest(
                pipelineName,
                request.pipelineDescription(),
                List.of(stepRequest)
        );

        PipelineDetailResponse pipeline = pipelineService.createPipeline(createReq, userId);

        // 5. Create schedule trigger if requested
        Long triggerId = null;
        if (request.schedule() != null && request.schedule().cronExpression() != null
                && !request.schedule().cronExpression().isBlank()) {
            String triggerName = (request.schedule().name() != null && !request.schedule().name().isBlank())
                    ? request.schedule().name()
                    : pipelineName + " 스케줄";

            CreateTriggerRequest triggerReq = new CreateTriggerRequest(
                    triggerName,
                    TriggerType.SCHEDULE,
                    request.schedule().description(),
                    Map.of("cron", request.schedule().cronExpression())
            );

            TriggerResponse triggerResponse = triggerService.createTrigger(pipeline.id(), triggerReq, userId);
            triggerId = triggerResponse.id();
        }

        // 6. Execute immediately if requested
        Long executionId = null;
        if (request.executeImmediately()) {
            PipelineExecutionResponse execution = pipelineService.executePipeline(pipeline.id(), userId);
            executionId = execution.id();
        }

        return new ApiImportResponse(pipeline.id(), executionId, triggerId);
    }
}
