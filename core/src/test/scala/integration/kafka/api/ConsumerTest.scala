/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package kafka.api

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.ConsumerRebalanceCallback
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.CommitType
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.clients.consumer.NoOffsetForPartitionException

import kafka.utils.{ShutdownableThread, TestUtils, Logging}
import kafka.server.OffsetManager

import java.util.ArrayList
import org.junit.Assert._

import scala.collection.JavaConversions._


/**
 * Integration tests for the new consumer that cover basic usage as well as server failures
 */
class ConsumerTest extends IntegrationTestHarness with Logging {

  val producerCount = 1
  val consumerCount = 2
  val serverCount = 3

  val topic = "topic"
  val part = 0
  val tp = new TopicPartition(topic, part)

  // configure the servers and clients
  this.serverConfig.setProperty("controlled.shutdown.enable", "false") // speed up shutdown
  this.serverConfig.setProperty("offsets.topic.replication.factor", "3") // don't want to lose offset
  this.serverConfig.setProperty("offsets.topic.num.partitions", "1")
  this.producerConfig.setProperty(ProducerConfig.ACKS_CONFIG, "all")
  this.consumerConfig.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "my-test")
  this.consumerConfig.setProperty(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 4096.toString)
  this.consumerConfig.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
  
  override def setUp() {
    super.setUp()

    // create the test topic with all the brokers as replicas
    TestUtils.createTopic(this.zkClient, topic, 1, serverCount, this.servers)
  }

  def testSimpleConsumption() {
    val numRecords = 10000
    sendRecords(numRecords)

    assertEquals(0, this.consumers(0).subscriptions.size)
    this.consumers(0).subscribe(tp)
    assertEquals(1, this.consumers(0).subscriptions.size)
    
    this.consumers(0).seek(tp, 0)
    consumeRecords(this.consumers(0), numRecords = numRecords, startingOffset = 0)
  }

  def testAutoOffsetReset() {
    sendRecords(1)
    this.consumers(0).subscribe(tp)
    consumeRecords(this.consumers(0), numRecords = 1, startingOffset = 0)
  }

  def testSeek() {
    val consumer = this.consumers(0)
    val totalRecords = 50L
    sendRecords(totalRecords.toInt)
    consumer.subscribe(tp)

    consumer.seekToEnd(tp)
    assertEquals(totalRecords, consumer.position(tp))
    assertFalse(consumer.poll(totalRecords).iterator().hasNext)

    consumer.seekToBeginning(tp)
    assertEquals(0, consumer.position(tp), 0)
    consumeRecords(consumer, numRecords = 1, startingOffset = 0)

    val mid = totalRecords / 2
    consumer.seek(tp, mid)
    assertEquals(mid, consumer.position(tp))
    consumeRecords(consumer, numRecords = 1, startingOffset = mid.toInt)
  }

  def testGroupConsumption() {
    sendRecords(10)
    this.consumers(0).subscribe(topic)
    consumeRecords(this.consumers(0), numRecords = 1, startingOffset = 0)
  }

  def testPositionAndCommit() {
    sendRecords(5)

    // committed() on a partition with no committed offset throws an exception
    intercept[NoOffsetForPartitionException] {
      this.consumers(0).committed(new TopicPartition(topic, 15))
    }

    // position() on a partition that we aren't subscribed to throws an exception
    intercept[IllegalArgumentException] {
      this.consumers(0).position(new TopicPartition(topic, 15))
    }

    this.consumers(0).subscribe(tp)

    assertEquals("position() on a partition that we are subscribed to should reset the offset", 0L, this.consumers(0).position(tp))
    this.consumers(0).commit(CommitType.SYNC)
    assertEquals(0L, this.consumers(0).committed(tp))

    consumeRecords(this.consumers(0), 5, 0)
    assertEquals("After consuming 5 records, position should be 5", 5L, this.consumers(0).position(tp))
    this.consumers(0).commit(CommitType.SYNC)
    assertEquals("Committed offset should be returned", 5L, this.consumers(0).committed(tp))

    sendRecords(1)

    // another consumer in the same group should get the same position
    this.consumers(1).subscribe(tp)
    consumeRecords(this.consumers(1), 1, 5)
  }

  def testPartitionsFor() {
    val numParts = 2
    TestUtils.createTopic(this.zkClient, "part-test", numParts, 1, this.servers)
    val parts = this.consumers(0).partitionsFor("part-test")
    assertNotNull(parts)
    assertEquals(2, parts.length)
    assertNull(this.consumers(0).partitionsFor("non-exist-topic"))
  }

  // TODO: fix test after fixing consumer-side Coordinator logic
  def failingTestPartitionReassignmentCallback() {
    val callback = new TestConsumerReassignmentCallback()
    this.consumerConfig.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "200"); // timeout quickly to avoid slow test
    val consumer0 = new KafkaConsumer(this.consumerConfig, callback, new ByteArrayDeserializer(), new ByteArrayDeserializer())
    consumer0.subscribe(topic)
        
    // the initial subscription should cause a callback execution
    while(callback.callsToAssigned == 0)
      consumer0.poll(50)
    
    // get metadata for the topic
    var parts = consumer0.partitionsFor(OffsetManager.OffsetsTopicName)
    while(parts == null)
      parts = consumer0.partitionsFor(OffsetManager.OffsetsTopicName)
    assertEquals(1, parts.size)
    assertNotNull(parts(0).leader())
    
    // shutdown the coordinator
    val coordinator = parts(0).leader().id()
    this.servers(coordinator).shutdown()
    
    // this should cause another callback execution
    while(callback.callsToAssigned < 2)
      consumer0.poll(50)
    assertEquals(2, callback.callsToAssigned)
    assertEquals(2, callback.callsToRevoked)

    consumer0.close()
  }

  private class TestConsumerReassignmentCallback extends ConsumerRebalanceCallback {
    var callsToAssigned = 0
    var callsToRevoked = 0
    def onPartitionsAssigned(consumer: Consumer[_,_], partitions: java.util.Collection[TopicPartition]) {
      info("onPartitionsAssigned called.")
      callsToAssigned += 1
    }
    def onPartitionsRevoked(consumer: Consumer[_,_], partitions: java.util.Collection[TopicPartition]) {
      info("onPartitionsRevoked called.")
      callsToRevoked += 1
    } 
  }

  private def sendRecords(numRecords: Int) {
    val futures = (0 until numRecords).map { i =>
      this.producers(0).send(new ProducerRecord(topic, part, i.toString.getBytes, i.toString.getBytes))
    }
    futures.map(_.get)
  }

  private def consumeRecords(consumer: Consumer[Array[Byte], Array[Byte]], numRecords: Int, startingOffset: Int) {
    val records = new ArrayList[ConsumerRecord[Array[Byte], Array[Byte]]]()
    val maxIters = numRecords * 300
    var iters = 0
    while (records.size < numRecords) {
      for (record <- consumer.poll(50))
        records.add(record)
      if(iters > maxIters)
        throw new IllegalStateException("Failed to consume the expected records after " + iters + " iterations.")
      iters += 1
    }
    for (i <- 0 until numRecords) {
      val record = records.get(i)
      val offset = startingOffset + i
      assertEquals(topic, record.topic())
      assertEquals(part, record.partition())
      assertEquals(offset.toLong, record.offset())
    }
  }

}