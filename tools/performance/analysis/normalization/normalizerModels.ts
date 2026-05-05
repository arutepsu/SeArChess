export interface NormalizerContext {
  testType: string;
  scenarioName: string;
  timestamp?: string;
  maxUsers: number;
  duration: string;
  rampUpPattern: string;
  cpuUsagePercent: number;
  memoryUsagePercent: number;
  dbPoolUsagePercent?: number;
}
