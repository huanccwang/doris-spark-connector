// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.spark.listener

import org.apache.doris.spark.load.DorisStreamLoad
import org.apache.doris.spark.sql.Utils
import org.apache.spark.scheduler._
import org.apache.spark.util.CollectionAccumulator
import org.slf4j.{Logger, LoggerFactory}

import java.time.Duration
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Failure, Success}

class DorisTransactionListener(preCommittedTxnAcc: CollectionAccumulator[Long], dorisStreamLoad: DorisStreamLoad, sinkTnxIntervalMs: Int, sinkTxnRetries: Int)
  extends SparkListener {

  val logger: Logger = LoggerFactory.getLogger(classOf[DorisTransactionListener])

  override def onJobEnd(jobEnd: SparkListenerJobEnd): Unit = {
    val txnIds: mutable.Buffer[Long] = preCommittedTxnAcc.value.asScala
    val failedTxnIds = mutable.Buffer[Long]()
    jobEnd.jobResult match {
      // if job succeed, commit all transactions
      case JobSucceeded =>
        if (txnIds.isEmpty) {
          logger.warn("job run succeed, but there is no pre-committed txn ids")
          return
        }
        logger.info("job run succeed, start committing transactions")
        txnIds.foreach(txnId =>
          Utils.retry(sinkTxnRetries, Duration.ofMillis(sinkTnxIntervalMs), logger) {
            dorisStreamLoad.commit(txnId)
          } () match {
            case Success(_) => // do nothing
            case Failure(_) => failedTxnIds += txnId
          }
        )

        if (failedTxnIds.nonEmpty) {
          logger.error("uncommitted txn ids: {}", failedTxnIds.mkString(","))
        } else {
          logger.info("commit transaction success")
        }
      // if job failed, abort all pre committed transactions
      case _ =>
        if (txnIds.isEmpty) {
          logger.warn("job run failed, but there is no pre-committed txn ids")
          return
        }
        logger.info("job run failed, start aborting transactions")
        txnIds.foreach(txnId =>
          Utils.retry(sinkTxnRetries, Duration.ofMillis(sinkTnxIntervalMs), logger) {
            dorisStreamLoad.abortById(txnId)
          } () match {
            case Success(_) => // do nothing
            case Failure(_) => failedTxnIds += txnId
          })
        if (failedTxnIds.nonEmpty) {
          logger.error("not aborted txn ids: {}", failedTxnIds.mkString(","))
        } else {
          logger.info("abort transaction success")
        }
    }
  }

}
