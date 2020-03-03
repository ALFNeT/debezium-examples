package io.debezium.examples.kinesis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.amazonaws.services.kinesisfirehose.model.Record;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.kinesisfirehose.AmazonKinesisFirehose;
import com.amazonaws.services.kinesisfirehose.AmazonKinesisFirehoseClientBuilder;
import com.amazonaws.services.kinesisfirehose.model.PutRecordRequest;


import io.debezium.config.Configuration;
import io.debezium.connector.sqlserver.SqlServerConnectorConfig;
import io.debezium.embedded.EmbeddedEngine;
import io.debezium.relational.history.MemoryDatabaseHistory;
import io.debezium.util.Clock;

/**
 * Demo for using the Debezium Embedded API to send change events to Amazon Kinesis Firehose.
 */
public class ChangeDataSender implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChangeDataSender.class);

    private static final String APP_NAME = "kinesis";
    private static final String KINESIS_REGION_CONF_NAME = "kinesis.region";

    private final Configuration config;
    private final JsonConverter valueConverter;
    private final AmazonKinesisFirehose kinesisFirehoseClient;
    private final AmazonKinesis kinesisClient;
    private EmbeddedEngine engine;

    public ChangeDataSender() {
        config = Configuration.empty().withSystemProperties(Function.identity()).edit()
                .with(EmbeddedEngine.CONNECTOR_CLASS, "io.debezium.connector.sqlserver.SqlServerConnector")
                .with(EmbeddedEngine.ENGINE_NAME, APP_NAME)
                .with(SqlServerConnectorConfig.SERVER_NAME, APP_NAME)
                .with(SqlServerConnectorConfig.DATABASE_NAME, "trademe")

                // for demo purposes let's store offsets and history only in memory
                .with(EmbeddedEngine.OFFSET_STORAGE, "org.apache.kafka.connect.storage.MemoryOffsetBackingStore")
                .with(SqlServerConnectorConfig.DATABASE_HISTORY, MemoryDatabaseHistory.class.getName())
                .with("decimal.handling.mode", "string")

                // Send JSON without schema
                .with("schemas.enable", false)
                .build();

        valueConverter = new JsonConverter();
        valueConverter.configure(config.asMap(), false);

        final String regionName = config.getString(KINESIS_REGION_CONF_NAME);

        final AWSCredentialsProvider credentialsProvider = new ProfileCredentialsProvider("TestDataEngineeringAdministrator-Administrator");

        kinesisFirehoseClient = AmazonKinesisFirehoseClientBuilder.standard()
                .withCredentials(credentialsProvider)
                .withRegion(regionName)
                .build();
    }

    @Override
    public void run() {
        engine = EmbeddedEngine.create()
                .using(config)
                .using(this.getClass().getClassLoader())
                .using(Clock.SYSTEM)
                .notifying(this::sendRecord)
                .build();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(engine);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Requesting embedded engine to shut down");
            engine.stop();
        }));

        // the submitted task keeps running, only no more new ones can be added
        executor.shutdown();

        awaitTermination(executor);

        cleanUp();

        LOGGER.info("Engine terminated");
    }

    private void awaitTermination(ExecutorService executor) {
        try {
            while (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.info("Waiting another 10 seconds for the embedded engine to complete");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void cleanUp() {
        kinesisClient.shutdown();
    }

    private void sendRecord(SourceRecord record) {
        // We are interested only in data events not schema change events
        if (record.topic().equals(APP_NAME)) {
            return;
        }

        Schema schema = null;

        if ( null == record.keySchema() ) {
            LOGGER.error("The keySchema is missing. Something is wrong.");
            return;
        }

        // For deletes, the value node is null
        if ( null != record.valueSchema() ) {
            schema = SchemaBuilder.struct()
                    .field("key", record.keySchema())
                    .field("value", record.valueSchema())
                    .build();
        }
        else {
            schema = SchemaBuilder.struct()
                    .field("key", record.keySchema())
                    .build();
        }

        Struct message = new Struct(schema);
        message.put("key", record.key());

        if ( null != record.value() )
            message.put("value", record.value());

        ByteArrayOutputStream payloadStream = new ByteArrayOutputStream();

        byte[] lineFeed = {'\n'};

        try {
            payloadStream.write(valueConverter.fromConnectData("dummy", schema, message));
            payloadStream.write(lineFeed);
        } catch (IOException e) {
            e.printStackTrace();
        }

        byte[] payload = payloadStream.toByteArray();

        Record firehoseRecord = new Record().withData(ByteBuffer.wrap(payload));

        PutRecordRequest putRecord = new PutRecordRequest();

        putRecord.setDeliveryStreamName("test-eventtracking");
        putRecord.setRecord(firehoseRecord);

        kinesisFirehoseClient.putRecord(putRecord);
    }

    private String streamNameMapper(String topic) {
        return topic;
    }

    public static void main(String[] args) {
        new ChangeDataSender().run();
    }
}
