import type { JobStatusResponse } from '../types/job';
import { client } from './client';

export const jobsApi = {
  getJobStatus: (jobId: string) =>
    client.get<JobStatusResponse>(`/jobs/${jobId}/status`),
};
