package com.gromozeka.domain.service

import com.gromozeka.domain.model.TokenUsageStatistics

interface AiUsageReportService {
    suspend fun getReport(query: TokenUsageStatistics.ReportQuery): TokenUsageStatistics.Report
}
