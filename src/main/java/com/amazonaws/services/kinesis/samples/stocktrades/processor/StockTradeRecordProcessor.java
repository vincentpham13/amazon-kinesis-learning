/*
 * Copyright 2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazonaws.services.kinesis.samples.stocktrades.processor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import software.amazon.kinesis.exceptions.InvalidStateException;
import software.amazon.kinesis.exceptions.ShutdownException;
import software.amazon.kinesis.exceptions.ThrottlingException;
import software.amazon.kinesis.lifecycle.events.InitializationInput;
import software.amazon.kinesis.lifecycle.events.LeaseLostInput;
import software.amazon.kinesis.processor.RecordProcessorCheckpointer;
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
import software.amazon.kinesis.lifecycle.events.ShardEndedInput;
import software.amazon.kinesis.lifecycle.events.ShutdownRequestedInput;
import software.amazon.kinesis.processor.ShardRecordProcessor;

import com.amazonaws.services.kinesis.samples.stocktrades.model.StockTrade;
import software.amazon.kinesis.retrieval.KinesisClientRecord;

/**
 * Processes records retrieved from stock trades stream.
 *
 */
public class StockTradeRecordProcessor implements ShardRecordProcessor {

    private static final Log LOG = LogFactory.getLog(StockTradeRecordProcessor.class);

    private String kinesisShardId;

    // Reporting interval
    private static final long REPORTING_INTERVAL_MILLIS = 60000L; // 1 minute
    private long nextReportingTimeInMillis;

    // Checkpointing interval
    private static final long CHECKPOINT_INTERVAL_MILLIS = 60000L; // 1 minute
    private long nextCheckpointTimeInMillis;

    // Aggregates stats for stock trades
    private StockStats stockStats = new StockStats();

    @Override
    public void initialize(InitializationInput initializationInput) {
        kinesisShardId = initializationInput.shardId();
        System.out.println("Initializing record processor for shard: " + kinesisShardId);
        System.out.println("Initializing @ Sequence: " + initializationInput.extendedSequenceNumber().toString());

        nextReportingTimeInMillis = System.currentTimeMillis() + REPORTING_INTERVAL_MILLIS;
        nextCheckpointTimeInMillis = System.currentTimeMillis() + CHECKPOINT_INTERVAL_MILLIS;
    }

    @Override
    public void processRecords(ProcessRecordsInput processRecordsInput) {
        try {
            // System.out.println("Processing"+ processRecordsInput.records().size() + " record(s)");
            System.out.println("Processing " + processRecordsInput.records().size() + " record(s)");
            processRecordsInput.records().forEach(r -> processRecord(r));
            // If it is time to report stats as per the reporting interval, report stats
            if (System.currentTimeMillis() > nextReportingTimeInMillis) {
                reportStats();
                resetStats();
                nextReportingTimeInMillis = System.currentTimeMillis() + REPORTING_INTERVAL_MILLIS;
            }

            // Checkpoint once every checkpoint interval
            if (System.currentTimeMillis() > nextCheckpointTimeInMillis) {
                checkpoint(processRecordsInput.checkpointer());
                nextCheckpointTimeInMillis = System.currentTimeMillis() + CHECKPOINT_INTERVAL_MILLIS;
            }
        } catch (Throwable t) {
            LOG.error("Caught throwable while processing records. Aborting.");
            Runtime.getRuntime().halt(1);
        }

    }

    private void reportStats() {
        System.out.println("****** Shard " + kinesisShardId + " stats for last 1 minute ******\n" + stockStats + "\n"
                + "****************************************************************\n");
    }

    private void resetStats() {
        stockStats = new StockStats();
    }

    private void processRecord(KinesisClientRecord record) {
        byte[] arr = new byte[record.data().remaining()];
        record.data().get(arr);
        StockTrade trade = StockTrade.fromJsonAsBytes(arr);
        if (trade == null) {
            LOG.warn(
                    "Skipping record. Unable to parse record into StockTrade. Partition Key: " + record.partitionKey());
            return;
        }
        // System.out.println(trade);
        stockStats.addStockTrade(trade);
    }

    @Override
    public void leaseLost(LeaseLostInput leaseLostInput) {
        System.out.println("Lost lease, so terminating.");
    }

    @Override
    public void shardEnded(ShardEndedInput shardEndedInput) {
        try {
            // Important to checkpoint after reaching end of shard, so we can start
            // processing data from child shards.
            System.out.println("Reached shard end checkpointing.");
            shardEndedInput.checkpointer().checkpoint();
        } catch (ShutdownException | InvalidStateException e) {
            LOG.error("Exception while checkpointing at shard end. Giving up.", e);
        }
    }

    @Override
    public void shutdownRequested(ShutdownRequestedInput shutdownRequestedInput) {
        System.out.println("Scheduler is shutting down, checkpointing.");
        checkpoint(shutdownRequestedInput.checkpointer());
    }

    private void checkpoint(RecordProcessorCheckpointer checkpointer) {
        LOG.info("Checkpointing shard " + kinesisShardId);
        try {
            checkpointer.checkpoint();
        } catch (ShutdownException se) {
            // Ignore checkpoint if the processor instance has been shutdown (fail over).
            LOG.info("Caught shutdown exception, skipping checkpoint.", se);
        } catch (ThrottlingException e) {
            // Skip checkpoint when throttled. In practice, consider a backoff and retry
            // policy.
            LOG.error("Caught throttling exception, skipping checkpoint.", e);
        } catch (InvalidStateException e) {
            // This indicates an issue with the DynamoDB table (check for table, provisioned
            // IOPS).
            LOG.error("Cannot save checkpoint to the DynamoDB table used by the Amazon Kinesis Client Library.", e);
        }
    }

}
