/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.neo4j.kernel.monitoring;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import org.neo4j.logging.Log;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.scheduler.JobScheduler.JobHandle;

import static java.lang.String.format;
import static java.lang.System.nanoTime;
import static java.lang.Thread.currentThread;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.neo4j.util.Preconditions.checkState;
import static org.neo4j.util.Preconditions.requirePositive;

public final class VmPauseMonitor
{
    private final long measurementDurationNs;
    private final long stallAlertThresholdNs;
    private final Log log;
    private final JobScheduler jobScheduler;
    private final Consumer<VmPauseInfo> listener;
    private JobHandle job;

    public VmPauseMonitor( Duration measureInterval, Duration stallAlertThreshold, Log log, JobScheduler jobScheduler, Consumer<VmPauseInfo> listener )
    {
        this.measurementDurationNs = requirePositive( measureInterval.toNanos() );
        this.stallAlertThresholdNs = requirePositive( stallAlertThreshold.toNanos() );
        this.log = requireNonNull( log );
        this.jobScheduler = jobScheduler;
        this.listener = requireNonNull( listener );
    }

    public void start()
    {
        log.debug( "Starting VM pause monitor" );
        checkState( job == null, "VM pause monitor is already started" );
        job = jobScheduler.schedule( JobScheduler.Groups.vmPauseMonitor, this::run );
    }

    public void stop()
    {
        log.debug( "Stopping VM pause monitor" );
        checkState( job != null, "VM pause monitor is not started" );
        job.cancel( true );
        try
        {
            job.waitTermination();
        }
        catch ( InterruptedException ignore )
        {
            currentThread().interrupt();
        }
        catch ( ExecutionException e )
        {
            log.debug( "VM pause monitor job failed", e );
        }
    }

    private void run()
    {
        try
        {
            monitor();
        }
        catch ( InterruptedException ignore )
        {
            log.debug( "VM pause monitor stopped" );
        }
        catch ( RuntimeException e )
        {
            log.debug( "VM pause monitor failed", e );
        }
    }

    private void monitor() throws InterruptedException
    {
        GcStats lastGcStats = getGcStats();
        long nextCheckPoint = nanoTime() + measurementDurationNs;

        while ( !currentThread().isInterrupted() )
        {
            NANOSECONDS.sleep( measurementDurationNs );
            final long now = nanoTime();
            final long pauseNs = now - nextCheckPoint;
            nextCheckPoint = now + measurementDurationNs;

            final GcStats gcStats = getGcStats();
            if ( pauseNs >= stallAlertThresholdNs )
            {
                final VmPauseInfo pauseInfo = new VmPauseInfo(
                        NANOSECONDS.toMillis( pauseNs ),
                        gcStats.time - lastGcStats.time,
                        gcStats.count - lastGcStats.count
                );
                listener.accept( pauseInfo );
            }
            lastGcStats = gcStats;
        }
    }

    public static class VmPauseInfo
    {
        private final long pauseTime;
        private final long gcTime;
        private final long gcCount;

        VmPauseInfo( long pauseTime, long gcTime, long gcCount )
        {
            this.pauseTime = pauseTime;
            this.gcTime = gcTime;
            this.gcCount = gcCount;
        }

        public long getPauseTime()
        {
            return pauseTime;
        }

        @Override
        public String toString()
        {
            return format( "{pauseTime=%d, gcTime=%d, gcCount=%d}", pauseTime, gcTime, gcCount );
        }
    }

    private static GcStats getGcStats()
    {
        long time = 0;
        long count = 0;
        for ( GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans() )
        {
            time += gcBean.getCollectionTime();
            count += gcBean.getCollectionCount();
        }
        return new GcStats( time, count );
    }

    private static class GcStats
    {
        private final long time;
        private final long count;

        private GcStats( long time, long count )
        {
            this.time = time;
            this.count = count;
        }
    }
}
