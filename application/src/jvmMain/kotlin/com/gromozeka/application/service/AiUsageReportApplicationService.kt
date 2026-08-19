package com.gromozeka.application.service

import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.service.AiUsageReportService
import org.springframework.stereotype.Service

@Service
class AiUsageReportApplicationService(
    private val repository: TokenUsageStatisticsRepository,
) : AiUsageReportService {
    override suspend fun getReport(query: TokenUsageStatistics.ReportQuery): TokenUsageStatistics.Report =
        repository.getReport(query)
}
