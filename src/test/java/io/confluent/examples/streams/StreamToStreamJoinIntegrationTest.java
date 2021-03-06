/*
 * Copyright Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.examples.streams;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.Joined;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.test.TestUtils;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import io.confluent.examples.streams.kafka.EmbeddedSingleNodeKafkaCluster;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test that demonstrates how to perform a join between two KStreams.
 *
 * Note: This example uses lambda expressions and thus works with Java 8+ only.
 */
public class StreamToStreamJoinIntegrationTest {

  private static final String adImpressionsTopic = "adImpressions";
  private static final String adClicksTopic = "adClicks";
  private static final String outputTopic = "output-topic";

  @Test
  public void shouldJoinTwoStreams() throws Exception {
    // Input 1: Ad impressions
    final List<KeyValue<String, String>> inputAdImpressions = Arrays.asList(
      new KeyValue<>("car-advertisement", "shown"),
      new KeyValue<>("newspaper-advertisement", "shown"),
      new KeyValue<>("gadget-advertisement", "shown")
    );

    // Input 2: Ad clicks
    final List<KeyValue<String, String>> inputAdClicks = Arrays.asList(
      new KeyValue<>("newspaper-advertisement", "clicked"),
      new KeyValue<>("gadget-advertisement", "clicked"),
      new KeyValue<>("newspaper-advertisement", "clicked")
    );

    final List<KeyValue<String, String>> expectedResults = Arrays.asList(
      new KeyValue<>("car-advertisement", "shown/not-clicked-yet"),
      new KeyValue<>("newspaper-advertisement", "shown/not-clicked-yet"),
      new KeyValue<>("gadget-advertisement", "shown/not-clicked-yet"),
      new KeyValue<>("newspaper-advertisement", "shown/clicked"),
      new KeyValue<>("gadget-advertisement", "shown/clicked"),
      new KeyValue<>("newspaper-advertisement", "shown/clicked")
    );

    //
    // Step 1: Configure and start the processor topology.
    //
    final Properties streamsConfiguration = new Properties();
    streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "stream-stream-join-lambda-integration-test");
    streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy config");
    streamsConfiguration.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
    streamsConfiguration.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
    // The commit interval for flushing records to state stores and downstream must be lower than
    // this integration test's timeout (30 secs) to ensure we observe the expected processing results.
    streamsConfiguration.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 10 * 1000);
    streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    // Use a temporary directory for storing state, which will be automatically removed after the test.
    streamsConfiguration.put(StreamsConfig.STATE_DIR_CONFIG, TestUtils.tempDirectory().getAbsolutePath());

    final StreamsBuilder builder = new StreamsBuilder();
    final KStream<String, String> alerts = builder.stream(adImpressionsTopic);
    final KStream<String, String> incidents = builder.stream(adClicksTopic);

    // In this example, we opt to perform an OUTER JOIN between the two streams.  We picked this
    // join type to show how the Streams API will send further join updates downstream whenever,
    // for the same join key (e.g. "newspaper-advertisement"), we receive an update from either of
    // the two joined streams during the defined join window.
    final KStream<String, String> impressionsAndClicks = alerts.outerJoin(
      incidents,
      (impressionValue, clickValue) ->
        (clickValue == null)? impressionValue + "/not-clicked-yet": impressionValue + "/" + clickValue,
      // KStream-KStream joins are always windowed joins, hence we must provide a join window.
      JoinWindows.of(Duration.ofSeconds(5)),
      // In this specific example, we don't need to define join serdes explicitly because the key, left value, and
      // right value are all of type String, which matches our default serdes configured for the application.  However,
      // we want to showcase the use of `Joined.with(...)` in case your code needs a different type setup.
      Joined.with(
        Serdes.String(), /* key */
        Serdes.String(), /* left value */
        Serdes.String()  /* right value */
      )
    );

    // Write the results to the output topic.
    impressionsAndClicks.to(outputTopic);

    final TopologyTestDriver topologyTestDriver = new TopologyTestDriver(builder.build(), streamsConfiguration);

    //
    // Step 2: Publish ad impressions.
    //
    IntegrationTestUtils.produceKeyValuesSynchronously(
      adImpressionsTopic,
      inputAdImpressions,
      topologyTestDriver,
      new StringSerializer(),
      new StringSerializer()
    );

    //
    // Step 3: Publish ad clicks.
    //
    IntegrationTestUtils.produceKeyValuesSynchronously(
      adClicksTopic,
      inputAdClicks,
      topologyTestDriver,
      new StringSerializer(),
      new StringSerializer()
    );

    //
    // Step 4: Verify the application's output data.
    //
    final List<KeyValue<String, String>> actualResults =
      IntegrationTestUtils.drainStreamOutput(
        outputTopic,
        topologyTestDriver,
        new StringDeserializer(),
        new StringDeserializer()
      );
    assertThat(actualResults).containsExactlyElementsOf(expectedResults);
  }

}
