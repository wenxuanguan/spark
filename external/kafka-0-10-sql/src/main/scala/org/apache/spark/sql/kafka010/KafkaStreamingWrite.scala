/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.kafka010

import java.{util => ju}

import org.apache.hadoop.fs.Path
import org.apache.kafka.clients.producer.ProducerConfig

import org.apache.spark.SparkEnv
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.streaming.OffsetSeqLog
import org.apache.spark.sql.kafka010.KafkaWriter.validateQuery
import org.apache.spark.sql.sources.v2.writer._
import org.apache.spark.sql.sources.v2.writer.streaming.{StreamingDataWriterFactory, StreamingWrite}
import org.apache.spark.sql.types.StructType
import org.apache.spark.storage.BlockManagerId

/**
 * A [[StreamingWrite]] for Kafka transactional writing. Responsible for generating
 * the transactional writer factory.
 *
 * @param topic The topic this writer is responsible for. If None, topic will be inferred from
 *              a `topic` field in the incoming data.
 * @param producerParams Parameters for Kafka producers in each task.
 * @param schema The schema of the input data.
 */
private[kafka010] class KafkaTransactionStreamingWrite(
    topic: Option[String],
    producerParams: ju.Map[String, Object],
    schema: StructType)
  extends StreamingWrite with Logging {
  private var initialized = false
  private var transactionalIdSuffix: String =
    KafkaTransactionStreamingWrite.generateTransactionIdSuffix(producerParams)
  private lazy val metaDataLog = {
    val sparkSession = SparkSession.getActiveSession.get
    KafkaTransactionStreamingWrite.getTransactionMetaDataLog(producerParams,
      sparkSession)
  }

  validateQuery(schema.toAttributes, producerParams, topic)
  updateProducerParams()

  override def createStreamingWriterFactory(): StreamingDataWriterFactory = {
    if (initialized) {
      KafkaTransactionStreamWriterFactory(topic, producerParams, schema, transactionalIdSuffix)
    } else {
      val writerFactory = {
        val sparkSession = SparkSession.getActiveSession.get
        val batchId = KafkaTransactionStreamingWrite.getCurrentBatchId(producerParams, sparkSession)
        val latestLog = metaDataLog.getLatest()
        // TODO: log wanr if user defined transaction id not equal to stored in hdfs
        
        if (latestLog.isEmpty) {
          KafkaTransactionStreamWriterFactory(topic, producerParams, schema,
            transactionalIdSuffix)
        } else {
          val latestBatchId = latestLog.get._1
          val metaData = latestLog.get._2
          if (latestBatchId > batchId) {
            // TODO: 参考HDFS meta data有问题
            throw new Exception("metadata corrupted.")
          }

          if (latestLog.get._1 < batchId) {
            val tranId = metaData.head.transactionalId
            transactionalIdSuffix = ProducerTransactionMetaData.toTransactionalIdSuffix(tranId)
            KafkaTransactionStreamWriterFactory(topic, producerParams, schema,
              transactionalIdSuffix)
          } else {
            // for test
            System.err.println("###start to resume")
            //
            val resumedTransId = metaData.head.transactionalId
            val resumedTranslIdSuffix =
              ProducerTransactionMetaData.toTransactionalIdSuffix(resumedTransId)
            KafkaStreamTransactionResumeWriterFactory(topic, producerParams, schema,
              resumedTranslIdSuffix, metaData)
          }
        }
      }
      initialize()
      writerFactory
    }
  }

  override def commit(epochId: Long, messages: Array[WriterCommitMessage]): Unit = {
    // for test
    if (producerParams.containsKey("test.timestamp1")) {
      val now = System.currentTimeMillis()
      val start = producerParams.get("test.timestamp1").toString.toLong
      val end = start + 1000 * 10
      if (now > start && now < end) {
        throw new Exception("***for test1")
      }
    }
    // end test

    val errorcommit = messages.exists(_.isInstanceOf[ErrorCommitMessage])
    if (errorcommit) {
      // TODO: add producer meta in exception
      throw new Exception("Fail to resume transaction.")
    }

    val needCommit = messages.nonEmpty &&
      messages.exists(_.isInstanceOf[ProducerTransactionMetaData])
    if (needCommit) {
      val metaDatas = messages.filter(_.isInstanceOf[ProducerTransactionMetaData])
        .map(_.asInstanceOf[ProducerTransactionMetaData])
      metaDataLog.add(epochId, metaDatas)

      // for test
      if (producerParams.containsKey("test.timestamp2")) {
        val now = System.currentTimeMillis()
        val start = producerParams.get("test.timestamp2").toString.toLong
        val end = start + 1000 * 10
        if (now > start && now < end) {
          throw new Exception("***for test2")
        }
      }
      // end test

      // TODO: threadpool and fail if get exception
      // for test add annotation
      /*metaDatas.foreach(metaData => {
        // TODO: CachedKafkaProducer.getOrCreate extra 操作
        val config = new ju.HashMap[String, Object]()
        config.putAll(producerParams)
        config.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, metaData.transactionalId)
        val producer = new InternalKafkaProducer(config)
        producer.resumeTransaction(metaData.producerId, metaData.epoch)
        producer.commitTransaction()
      })*/
      // for test
      metaDatas.zipWithIndex.foreach{case (metaData, index) =>
        if (producerParams.containsKey("test.timestamp3") && index % 2 == 1) {

            val now = System.currentTimeMillis()
            val start = producerParams.get("test.timestamp3").toString.toLong
            val end = start + 1000 * 10
            if (now > start && now < end) {
              throw new Exception("***for test3")
            }

        }
        val config = new ju.HashMap[String, Object]()
        config.putAll(producerParams)
        config.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, metaData.transactionalId)
        val producer = new InternalKafkaProducer(config)
        System.err.println(s"start to resumt. meta:${metaData}")
        producer.resumeTransaction(metaData.producerId, metaData.epoch)
        System.err.println(s"finish resume. meta:${metaData}")
        System.err.println(s"start to commit. meta:${metaData}")
        producer.commitTransaction()
        System.err.println(s"finish commit. meta:${metaData}")
      }
      //

      /*val config = new ju.HashMap[String, Object]()
      config.putAll(producerParams)
      val sparkSession = SparkSession.getActiveSession.get
      val aliveExecutors = KafkaTransactionStreamingWrite.getExecutors(sparkSession)
      val executorNum = if (aliveExecutors.nonEmpty) aliveExecutors.size else 1
      val executorMetaData = metaDatas.groupBy(
        metaData => ProducerTransactionMetaData.toExecutorId(metaData.transactionalId))
      val transactionSuffix =
        ProducerTransactionMetaData.toTransactionalIdSuffix(metaDatas.head.transactionalId)
      val successExecutors = sparkSession.sparkContext
        .parallelize( 0 until executorNum, executorNum)
        .map(_ => {
          val executorId = SparkEnv.get.executorId
          if (executorMetaData.contains(executorId)) {
            executorMetaData(executorId).foreach(metaData => {
              config.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, metaData.transactionalId)
              val producer = CachedKafkaProducer.getOrCreate(config)
              producer.commitTransaction()
            })

            TaskIndexGenerator.resetTaskIndex(transactionSuffix)
          }
          executorId
        }).collect()

      val failedExecutors = executorMetaData.keys.toList.diff(successExecutors)
      if (failedExecutors.nonEmpty) {
        throw new Exception("Fail to commit the writing job, " +
          s"since kafka producer failed to commit transaction in " +
          s"executor[${failedExecutors.mkString(",")}].")
      }*/
    }
  }

  override def abort(epochId: Long, messages: Array[WriterCommitMessage]): Unit = {
    val sparkSession = SparkSession.getActiveSession.get
    val batchId = KafkaTransactionStreamingWrite.getCurrentBatchId(producerParams, sparkSession)
    val metaData = metaDataLog.get(batchId)
    val needAbort = messages.nonEmpty && messages.head.isInstanceOf[ProducerTransactionMetaData] &&
      metaData.isEmpty
    if (needAbort) {
      val metaDatas = messages.filter(_.isInstanceOf[ProducerTransactionMetaData])
        .map(_.asInstanceOf[ProducerTransactionMetaData])

      metaDatas.foreach(metaData => {
        // TODO: CachedKafkaProducer.getOrCreate extra 操作
        val config = new ju.HashMap[String, Object]()
        config.putAll(producerParams)
        config.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, metaData.transactionalId)
        val producer = new InternalKafkaProducer(config)
        producer.resumeTransaction(metaData.producerId, metaData.epoch)

        // TODO: add to Utils
        import util.control.NonFatal
        try {
          producer.abortTransaction()
        } catch {
          case NonFatal(t) =>
            logError(s"Uncaught exception in thread ${Thread.currentThread().getName}", t)
        } finally {
          producer.close()
        }
      })
    }
  }

  override def getOptionalPartitionNum: Integer = {
    if (producerParams.containsKey(KafkaTransactionStreamingWrite.PRODUCER_CREATE_FACTOR_CONFIG)) {
      producerParams.get(
        KafkaTransactionStreamingWrite.PRODUCER_CREATE_FACTOR_CONFIG).toString.toInt
    } else {
      KafkaTransactionStreamingWrite.DEFAULT_PRODUCER_CREATE_FACTOR
    }
  }

  private def initialize(): Unit = {
    initialized = true
  }

  private def updateProducerParams(): Unit = {
    if (!producerParams.containsKey(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG)) {
      producerParams.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG,
        KafkaTransactionStreamingWrite.DEFAULT_KAFKA_BROKER_TRANSACTION_MAX_TIMEOUT_MS)
    } else {
      val timeout = producerParams.get(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG).toString.toLong
      val recommendTimeout =
        KafkaTransactionStreamingWrite.DEFAULT_KAFKA_BROKER_TRANSACTION_MAX_TIMEOUT_MS.toLong
      if (timeout < recommendTimeout) {
        logWarning(s"Value of config ${ProducerConfig.TRANSACTION_TIMEOUT_CONFIG} is $timeout, " +
          s"less than recommend value: " +
          s"${KafkaTransactionStreamingWrite.DEFAULT_KAFKA_BROKER_TRANSACTION_MAX_TIMEOUT_MS}," +
          s" recover from failure when restart application will fail and lost data " +
          s"if exceed $timeout.")
      }
    }
  }
}

/**
 * A [[StreamingWrite]] for Kafka writing. Responsible for generating the writer factory.
 *
 * @param topic The topic this writer is responsible for. If None, topic will be inferred from
 *              a `topic` field in the incoming data.
 * @param producerParams Parameters for Kafka producers in each task.
 * @param schema The schema of the input data.
 */
private[kafka010] class KafkaStreamingWrite(
    topic: Option[String],
    producerParams: ju.Map[String, Object],
    schema: StructType)
  extends StreamingWrite {

  validateQuery(schema.toAttributes, producerParams, topic)

  override def createStreamingWriterFactory(): KafkaStreamWriterFactory =
    KafkaStreamWriterFactory(topic, producerParams, schema)

  override def commit(epochId: Long, messages: Array[WriterCommitMessage]): Unit = {}
  override def abort(epochId: Long, messages: Array[WriterCommitMessage]): Unit = {}
}

/**
 * A [[StreamingDataWriterFactory]] for Kafka transactional writing. Will be serialized
 * and sent to executors to generate the per-task transactional data writers.
 * @param topic The topic that should be written to. If None, topic will be inferred from
 *              a `topic` field in the incoming data.
 * @param producerParams Parameters for Kafka producers in each task.
 * @param schema The schema of the input data.
 */
private case class KafkaTransactionStreamWriterFactory(
    topic: Option[String],
    producerParams: ju.Map[String, Object],
    schema: StructType,
    transactionSuffix: String)
  extends StreamingDataWriterFactory {

  override def createWriter(
      partitionId: Int,
      taskId: Long,
      epochId: Long): DataWriter[InternalRow] = {
    val executorId = SparkEnv.get.executorId
    import org.apache.spark.TaskContext
    val taskAttempt = TaskContext.get().attemptNumber()
    // TODO: get transaction id from queue or create one if queue.poll() == null or task taskAttempt > 0
//    val taskIndex = TaskIndexGenerator.getTaskIndex(transactionSuffix)
    val transactionalId = ProducerTransactionMetaData.toTransactionId(executorId, partitionId.toString,
      transactionSuffix)
    producerParams.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionalId)
    new KafkaTransactionDataWriter(topic, producerParams, schema.toAttributes)
  }
}

/**
 * A [[StreamingDataWriterFactory]] for resuming Kafka transaction. Will be serialized and sent to
 * executors to resume transaction.
 * @param topic The topic that should be written to. If None, topic will be inferred from
 *              a `topic` field in the incoming data.
 * @param producerParams Parameters for Kafka producers in each task.
 * @param schema The schema of the input data.
 */
private case class KafkaStreamTransactionResumeWriterFactory(
    topic: Option[String],
    producerParams: ju.Map[String, Object],
    schema: StructType,
    transactionSuffix: String,
    metaDatas: Array[ProducerTransactionMetaData])
  extends StreamingDataWriterFactory {

  override def createWriter(
      partitionId: Int,
      taskId: Long,
      epochId: Long): DataWriter[InternalRow] = {
    val metaData: ProducerTransactionMetaData = metaDatas(partitionId)
    producerParams.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, metaData.transactionalId)
    new KafkaTransactionResumeDataWriter(topic, producerParams, schema.toAttributes, metaData)
  }
}

/**
 * A [[StreamingDataWriterFactory]] for Kafka writing. Will be serialized and sent to executors to
 * generate the per-task data writers.
 * @param topic The topic that should be written to. If None, topic will be inferred from
 *              a `topic` field in the incoming data.
 * @param producerParams Parameters for Kafka producers in each task.
 * @param schema The schema of the input data.
 */
private case class KafkaStreamWriterFactory(
    topic: Option[String],
    producerParams: ju.Map[String, Object],
    schema: StructType)
  extends StreamingDataWriterFactory {

  override def createWriter(
      partitionId: Int,
      taskId: Long,
      epochId: Long): DataWriter[InternalRow] = {
    new KafkaDataWriter(topic, producerParams, schema.toAttributes)
  }
}

private[kafka010] object KafkaTransactionStreamingWrite {
  private val METADATA_DIR = "_kafka_producer_transaction_metadata"
  private val PRODUCER_CREATE_FACTOR_CONFIG = "producerCreateFactor"
  private val DEFAULT_PRODUCER_CREATE_FACTOR = 10

  // equals to the default value of config transaction.max.timeout.ms in Kafka broker
  private val DEFAULT_KAFKA_BROKER_TRANSACTION_MAX_TIMEOUT_MS = "900000"

  def getTransactionMetaDataLog(
      params: ju.Map[String, Object],
      sparkSession: SparkSession): ProducerTransactionMetaDataLog = {
    val checkpointLoc = params.get("checkpointLocation")
    val basePath = s"$checkpointLoc/sinks"
    val logPath = new Path(basePath, METADATA_DIR)
    new ProducerTransactionMetaDataLog(sparkSession, logPath.toUri.toString, params)
  }

  def getCurrentBatchId(params: ju.Map[String, Object], sparkSession: SparkSession): Long = {
    val checkpointLoc = params.get("checkpointLocation").toString
    val logPath = new Path(checkpointLoc, "offsets")
    val offsetLog = new OffsetSeqLog(sparkSession, logPath.toUri.toString)
    offsetLog.getLatest().get._1
  }

  def generateTransactionIdSuffix(params: ju.Map[String, Object]): String = {
      val userDefinedTransId = params.get(ProducerConfig.TRANSACTIONAL_ID_CONFIG)
      userDefinedTransId + ju.UUID.randomUUID().toString
  }

  // TODO: delete
  def getExecutors(sparkSession: SparkSession): Seq[BlockManagerId] = {
    val blockManager = sparkSession.sparkContext.env.blockManager
    blockManager.master.getPeers(blockManager.blockManagerId)
  }
}
