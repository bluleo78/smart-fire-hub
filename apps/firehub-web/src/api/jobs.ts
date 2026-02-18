import { client } from './client';
import type { JobStatusResponse } from '../types/job';

export const jobsApi = {
  getJobStatus: (jobId: string) =>
    client.get<JobStatusResponse>(`/jobs/${jobId}/status`),
};
