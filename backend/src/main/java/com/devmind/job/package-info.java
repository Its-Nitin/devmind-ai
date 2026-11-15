/**
 * Job module: a durable, Postgres-backed job queue with leases, heartbeats, retries and progress (ADR-0009).
 *
 * <p>Public API: {@link com.devmind.job.JobApi} to submit and read jobs, and the {@link com.devmind.job.JobHandler}
 * SPI that other modules implement to run a {@link com.devmind.job.JobType}.
 */
@NullMarked
package com.devmind.job;

import org.jspecify.annotations.NullMarked;
