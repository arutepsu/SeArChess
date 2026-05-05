export type BottleneckType = 'CPU_BOUND' | 'IO_BOUND' | 'CONTENTION' | 'SCALABILITY' | 'UNKNOWN';
export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW';
export type Verdict = 'SUCCESS' | 'NO_CHANGE' | 'REGRESSION';

export interface PerformanceMetadata {
  test_type: string;
  scenario_name: string;
  timestamp: string;
}

export interface LoadProfile {
  max_users: number;
  duration: string;
  ramp_up_pattern: string;
}

export interface Latency {
  p50: number;  // milliseconds
  p95: number;  // milliseconds
  p99: number;  // milliseconds
}

export interface Throughput {
  requests_per_second: number;
}

export interface Errors {
  error_rate: number;   // 0–1 float
  total_errors: number;
}

export interface SystemMetrics {
  cpu_usage_percent: number;     // 0–100
  memory_usage_percent: number;  // 0–100
}

export interface OptionalMetrics {
  db_pool_usage_percent?: number;  // 0–100
}

export interface PerformanceInput {
  metadata: PerformanceMetadata;
  load_profile: LoadProfile;
  latency: Latency;
  throughput: Throughput;
  errors: Errors;
  system: SystemMetrics;
  optional?: OptionalMetrics;
}

export interface Bottleneck {
  type: BottleneckType;
  confidence: Confidence;
}

export interface ReportSummary {
  p95_latency: number;
  error_rate: number;
  throughput: number;
}

export interface PerformanceReport {
  metadata: PerformanceMetadata;
  summary: ReportSummary;
  observations: string[];
  bottleneck: Bottleneck;
  evidence: string[];
  suggestions: string[];
  notes: string[];
}

export interface Improvement {
  latency_change_percent: number | null;
  error_change_percent: number | null;
  throughput_change_percent: number | null;
}

export interface PerformanceComparisonReport {
  baseline_summary: ReportSummary;
  optimized_summary: ReportSummary;
  improvement: Improvement;
  interpretation: string[];
  verdict: Verdict;
}
